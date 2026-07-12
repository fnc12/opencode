package studio.eugenezakharov.opencode.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import studio.eugenezakharov.opencode.MainActivity
import studio.eugenezakharov.opencode.R

/** Receives the FCM token + idle-session pushes from the relay. */
class ShubatMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistrar.deliver(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Foreground delivery: the system auto-shows notification-payload messages
        // only in the background, so display it here.
        val n = message.notification
        val title = n?.title ?: message.data["title"] ?: "Shubat"
        val body = n?.body ?: message.data["body"] ?: return
        ensureChannel()
        val sessionId = message.data["sessionId"]
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("sessionId", sessionId)
        // Distinct request code per session so a permission push and an idle push
        // for different sessions don't overwrite each other's extras.
        val pending = android.app.PendingIntent.getActivity(
            this, sessionId?.hashCode() ?: 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(message.messageId?.hashCode() ?: 0, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Session updates", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "session-updates"
    }
}
