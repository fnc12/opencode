package relayserver

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/appstore"
	"github.com/fnc12/opencode/packages/relay/internal/appstore/appstoretest"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

const (
	testBundle  = "studio.eugenezakharov.shubat"
	testProduct = "studio.eugenezakharov.shubat.pro.monthly"
	day         = 24 * 60 * 60
)

type appleFixture struct {
	srv    *Server
	ts     *httptest.Server
	ca     *appstoretest.CA
	pstore provision.Store
	astore *account.Store
}

func appleServer(t *testing.T, mutate func(*AppleIAPConfig)) *appleFixture {
	t.Helper()
	pstore, err := provision.NewSQLiteStore(filepath.Join(t.TempDir(), "p.db"))
	if err != nil {
		t.Fatal(err)
	}
	astore, err := account.NewStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { astore.Close() })
	ca := appstoretest.New(t)
	cfg := AppleIAPConfig{
		BundleID:     testBundle,
		ProductIDs:   []string{testProduct},
		AllowSandbox: true,
		Verifier:     &appstore.Verifier{Roots: ca.Roots},
	}
	if mutate != nil {
		mutate(&cfg)
	}
	srv := New(Config{
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision: pstore,
		Accounts:  astore,
		AppleIAP:  cfg,
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return &appleFixture{srv: srv, ts: ts, ca: ca, pstore: pstore, astore: astore}
}

// appleTxn is a transaction payload expiring `expires` from now, signed now.
func appleTxn(txID string, expires time.Duration) map[string]any {
	now := time.Now()
	return map[string]any{
		"transactionId":         txID,
		"originalTransactionId": "2000000000000001",
		"bundleId":              testBundle,
		"productId":             testProduct,
		"type":                  "Auto-Renewable Subscription",
		"environment":           "Sandbox",
		"purchaseDate":          now.UnixMilli(),
		"expiresDate":           now.Add(expires).UnixMilli(),
		"signedDate":            now.UnixMilli(),
	}
}

// accountTunnel makes an account with its free account-keyed tunnel.
func (f *appleFixture) accountTunnel(t *testing.T, email string) (string, provision.Tunnel) {
	t.Helper()
	acc, _, err := f.astore.AccountForEmail(email, time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	tun, err := f.pstore.MintFor(acc, "account:"+acc, time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	return acc, tun
}

func (f *appleFixture) upload(t *testing.T, tun provision.Tunnel, token, jws string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"tunnelId": tun.ID, "signedTransaction": jws})
	req, _ := http.NewRequest(http.MethodPost, f.ts.URL+"/api/iap/apple", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tunnel-Token", token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func (f *appleFixture) notify(t *testing.T, typ string, tx map[string]any) int {
	t.Helper()
	data := map[string]any{"bundleId": testBundle, "environment": "Sandbox"}
	if tx != nil {
		data["signedTransactionInfo"] = f.ca.Sign(t, tx)
	}
	return f.notifyRaw(t, f.ca.Sign(t, map[string]any{
		"notificationType": typ,
		"notificationUUID": "uuid-" + typ,
		"version":          "2.0",
		"signedDate":       time.Now().UnixMilli(),
		"data":             data,
	}))
}

func (f *appleFixture) notifyRaw(t *testing.T, signedPayload string) int {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"signedPayload": signedPayload})
	resp, err := http.Post(f.ts.URL+"/apple/notifications", "application/json", strings.NewReader(string(body)))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode
}

func TestAppleUploadEntitlesAccount(t *testing.T) {
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")
	tx := appleTxn("2000000000000001", 90*day*time.Second)

	code, out := f.upload(t, tun, tun.Token, f.ca.Sign(t, tx))
	if code != http.StatusOK {
		t.Fatalf("status = %d, want 200", code)
	}
	if got, want := int64(out["entitledUntil"].(float64)), tx["expiresDate"].(int64)/1000; got != want || out["active"] != true {
		t.Errorf("reply = %v, want entitledUntil %d active", out, want)
	}
	if !f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Fatal("account not Apple-entitled after upload")
	}

	// Entitlement = free OR PayPal OR Apple: after the free month lapses the
	// Apple subscription keeps the tunnel; after it expires the reaper revokes.
	if n := f.srv.reapExpiredFree(time.Now().Unix() + 60*day); n != 0 {
		t.Errorf("reaped %d while Apple-entitled, want 0", n)
	}
	if n := f.srv.reapExpiredFree(time.Now().Unix() + 100*day); n != 1 {
		t.Errorf("reaped %d after Apple expiry, want 1", n)
	}
}

func TestAppleUploadAuth(t *testing.T) {
	f := appleServer(t, nil)
	_, tun := f.accountTunnel(t, "ios@x.com")
	jws := f.ca.Sign(t, appleTxn("1", time.Hour))
	if code, _ := f.upload(t, tun, "wrong-token", jws); code != http.StatusUnauthorized {
		t.Errorf("wrong token: status = %d, want 401", code)
	}
	if code, _ := f.upload(t, tun, "", jws); code != http.StatusUnauthorized {
		t.Errorf("no token: status = %d, want 401", code)
	}
	ghost := provision.Tunnel{ID: "tun_nope"}
	if code, _ := f.upload(t, ghost, tun.Token, jws); code != http.StatusUnauthorized {
		t.Errorf("unknown tunnel: status = %d, want 401", code)
	}
}

func TestAppleUploadRejectsBadTransactions(t *testing.T) {
	f := appleServer(t, func(c *AppleIAPConfig) { c.AllowSandbox = false })
	acc, tun := f.accountTunnel(t, "ios@x.com")
	prod := func(mut func(map[string]any)) map[string]any {
		tx := appleTxn("1", time.Hour)
		tx["environment"] = "Production"
		if mut != nil {
			mut(tx)
		}
		return tx
	}
	cases := map[string]string{
		"foreign chain":   appstoretest.New(t).Sign(t, prod(nil)),
		"forged sig":      f.ca.SignKey(t, prod(nil), appstoretest.GenKey(t)),
		"other bundle":    f.ca.Sign(t, prod(func(m map[string]any) { m["bundleId"] = "com.example.other" })),
		"other product":   f.ca.Sign(t, prod(func(m map[string]any) { m["productId"] = "lifetime" })),
		"sandbox in prod": f.ca.Sign(t, appleTxn("1", time.Hour)),
		"not a jws":       "nope",
	}
	for name, jws := range cases {
		if code, _ := f.upload(t, tun, tun.Token, jws); code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400", name, code)
		}
	}
	if f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Fatal("a rejected transaction entitled the account")
	}
	// The same transaction from Production is accepted.
	if code, _ := f.upload(t, tun, tun.Token, f.ca.Sign(t, prod(nil))); code != http.StatusOK {
		t.Errorf("production transaction: status = %d, want 200", code)
	}
}

func TestAppleUploadExpiredOrRefunded(t *testing.T) {
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")

	expired := appleTxn("1", -time.Hour)
	code, out := f.upload(t, tun, tun.Token, f.ca.Sign(t, expired))
	if code != http.StatusOK || out["active"] != false || out["entitledUntil"].(float64) != 0 {
		t.Errorf("expired: status %d reply %v, want 200 inactive", code, out)
	}

	refunded := appleTxn("2", 30*day*time.Second)
	refunded["revocationDate"] = time.Now().UnixMilli()
	refunded["revocationReason"] = 0
	refunded["signedDate"] = time.Now().Add(time.Second).UnixMilli()
	if _, out := f.upload(t, tun, tun.Token, f.ca.Sign(t, refunded)); out["active"] != false {
		t.Errorf("refunded: reply %v, want inactive", out)
	}
	if f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Error("expired/refunded transaction entitled the account")
	}
}

func TestAppleUploadOnTunnelWithoutAccount(t *testing.T) {
	// The App Review demo tunnel is admin-minted, with no account behind it. A
	// purchase there must still be acknowledged, not fail in front of review.
	f := appleServer(t, nil)
	tun, err := f.pstore.Mint("demo", time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	code, out := f.upload(t, tun, tun.Token, f.ca.Sign(t, appleTxn("1", time.Hour)))
	if code != http.StatusOK || out["active"] != true {
		t.Fatalf("status %d reply %v, want 200 active", code, out)
	}
	sub, _, _ := f.astore.AppleSubscription("2000000000000001")
	if sub.AccountID != "tunnel:"+tun.ID {
		t.Errorf("owner = %q, want tunnel-scoped", sub.AccountID)
	}
}

func TestAppleNotificationsLifecycle(t *testing.T) {
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")
	now := func() int64 { return time.Now().Unix() }
	f.upload(t, tun, tun.Token, f.ca.Sign(t, appleTxn("1", time.Hour)))

	// Renewal extends the expiry.
	renew := appleTxn("2", 31*day*time.Second)
	renew["signedDate"] = time.Now().Add(time.Second).UnixMilli()
	if code := f.notify(t, "DID_RENEW", renew); code != http.StatusOK {
		t.Fatalf("DID_RENEW status = %d", code)
	}
	if until, _ := f.astore.AppleEntitledUntil(acc); until != renew["expiresDate"].(int64)/1000 {
		t.Errorf("after renewal until = %d, want %d", until, renew["expiresDate"].(int64)/1000)
	}

	// Turning auto-renew off (cancel) keeps access until the period ends.
	off := appleTxn("2", 31*day*time.Second)
	off["signedDate"] = time.Now().Add(2 * time.Second).UnixMilli()
	f.notify(t, "DID_CHANGE_RENEWAL_STATUS", off)
	if !f.astore.AppleActive(acc, now()) {
		t.Error("cancelling auto-renew must not cut the paid period")
	}

	// A refund revokes immediately, even if the transaction lacks revocationDate.
	refund := appleTxn("2", 31*day*time.Second)
	refund["signedDate"] = time.Now().Add(3 * time.Second).UnixMilli()
	if code := f.notify(t, "REFUND", refund); code != http.StatusOK {
		t.Fatalf("REFUND status = %d", code)
	}
	if f.astore.AppleActive(acc, now()) {
		t.Fatal("refunded subscription still entitles")
	}
	// The app re-uploading its older cached JWS must not undo the refund.
	f.upload(t, tun, tun.Token, f.ca.Sign(t, renew))
	if f.astore.AppleActive(acc, now()) {
		t.Error("stale upload undid the refund")
	}
}

func TestAppleNotificationRefundOfPastPeriod(t *testing.T) {
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")
	now := func() int64 { return time.Now().Unix() }
	first := appleTxn("1", time.Hour)
	f.upload(t, tun, tun.Token, f.ca.Sign(t, first))
	renew := appleTxn("2", 31*day*time.Second)
	renew["signedDate"] = time.Now().Add(time.Second).UnixMilli()
	f.notify(t, "DID_RENEW", renew)

	// Apple refunds the first period after the renewal: the current one stays.
	refund := appleTxn("1", time.Hour)
	refund["signedDate"] = time.Now().Add(2 * time.Second).UnixMilli()
	refund["revocationDate"] = time.Now().UnixMilli()
	if code := f.notify(t, "REFUND", refund); code != http.StatusOK {
		t.Fatalf("REFUND status = %d", code)
	}
	if !f.astore.AppleActive(acc, now()) {
		t.Fatal("refund of a past period revoked the paid current period")
	}
	if until, _ := f.astore.AppleEntitledUntil(acc); until != renew["expiresDate"].(int64)/1000 {
		t.Errorf("until = %d, want the renewal's %d", until, renew["expiresDate"].(int64)/1000)
	}
	// The app's upload of the current transaction is still answered as active.
	if code, resp := f.upload(t, tun, tun.Token, f.ca.Sign(t, renew)); code != http.StatusOK || resp["active"] != true {
		t.Errorf("current upload after past refund: %d %v", code, resp)
	}
}

func TestAppleNotificationExpired(t *testing.T) {
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")
	f.upload(t, tun, tun.Token, f.ca.Sign(t, appleTxn("1", time.Hour)))
	gone := appleTxn("1", -time.Minute)
	gone["signedDate"] = time.Now().Add(time.Second).UnixMilli()
	f.notify(t, "EXPIRED", gone)
	if f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Error("EXPIRED subscription still entitles")
	}
}

func TestAppleNotificationRejectsAndIgnores(t *testing.T) {
	f := appleServer(t, nil)
	if code := f.notifyRaw(t, appstoretest.New(t).Sign(t, map[string]any{"notificationType": "DID_RENEW"})); code != http.StatusBadRequest {
		t.Errorf("foreign signature: status = %d, want 400", code)
	}
	if code := f.notifyRaw(t, "garbage"); code != http.StatusBadRequest {
		t.Errorf("garbage: status = %d, want 400", code)
	}
	if code := f.notify(t, "TEST", nil); code != http.StatusOK {
		t.Errorf("TEST: status = %d, want 200", code)
	}
	// Notification about another app of the same developer: acknowledged, ignored.
	other := f.ca.Sign(t, map[string]any{
		"notificationType": "SUBSCRIBED", "signedDate": time.Now().UnixMilli(),
		"data": map[string]any{"bundleId": "com.example.vault", "signedTransactionInfo": f.ca.Sign(t, appleTxn("9", time.Hour))},
	})
	if code := f.notifyRaw(t, other); code != http.StatusOK {
		t.Errorf("other bundle: status = %d, want 200", code)
	}
	if _, found, _ := f.astore.AppleSubscription("2000000000000001"); found {
		t.Error("another app's notification was recorded")
	}
}

func TestAppleNotificationBeforeUpload(t *testing.T) {
	// SUBSCRIBED can reach the relay before the app uploads: recorded unbound,
	// then the upload binds it to the account.
	f := appleServer(t, nil)
	acc, tun := f.accountTunnel(t, "ios@x.com")
	tx := appleTxn("1", time.Hour)
	f.notify(t, "SUBSCRIBED", tx)
	if f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Fatal("unbound notification entitled an account")
	}
	f.upload(t, tun, tun.Token, f.ca.Sign(t, tx))
	if !f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Error("upload didn't bind the notified subscription")
	}
}

func TestAppleUploadRefreshesFromServerAPI(t *testing.T) {
	var ca *appstoretest.CA
	var apiTx map[string]any
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{
			"lastTransactions": []any{map[string]any{"signedTransactionInfo": ca.Sign(t, apiTx)}},
		}}})
	}))
	defer api.Close()

	der, _ := x509.MarshalPKCS8PrivateKey(appstoretest.GenKey(t))
	keyPath := filepath.Join(t.TempDir(), "iap.p8")
	os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600)
	client, err := appstore.NewClient(appstore.APIConfig{KeyPath: keyPath, KeyID: "K", IssuerID: "I", BundleID: testBundle})
	if err != nil {
		t.Fatal(err)
	}
	client.BaseURL = api.URL

	f := appleServer(t, func(c *AppleIAPConfig) { c.API = client })
	ca = f.ca
	acc, tun := f.accountTunnel(t, "ios@x.com")

	// The app holds a live-looking JWS, but Apple says it's been refunded since.
	apiTx = appleTxn("1", time.Hour)
	apiTx["revocationDate"] = time.Now().UnixMilli()
	apiTx["signedDate"] = time.Now().Add(time.Second).UnixMilli()
	code, out := f.upload(t, tun, tun.Token, f.ca.Sign(t, appleTxn("1", time.Hour)))
	if code != http.StatusOK || out["active"] != false {
		t.Fatalf("status %d reply %v, want Apple's refunded state", code, out)
	}
	if f.astore.AppleActive(acc, time.Now().Unix()) {
		t.Error("server API refund ignored")
	}
}

func TestAppleIAPDisabledWithoutProducts(t *testing.T) {
	f := appleServer(t, func(c *AppleIAPConfig) { c.ProductIDs = nil })
	resp, err := http.Post(f.ts.URL+"/api/iap/apple", "application/json", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusOK || resp.StatusCode == http.StatusBadRequest {
		t.Errorf("status = %d; endpoint should not be routed without product ids", resp.StatusCode)
	}
}
