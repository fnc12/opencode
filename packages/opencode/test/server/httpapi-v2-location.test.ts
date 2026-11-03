import { afterEach, describe, expect, test } from "bun:test"
import { Context, Schema } from "effect"
import { HttpApiApp } from "../../src/server/routes/instance/httpapi/server"
import { resetDatabase } from "../fixture/db"
import { disposeAllInstances, tmpdir } from "../fixture/fixture"

const context = Context.empty() as Context.Context<unknown>

function request(route: string, directory: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  headers.set("x-opencode-directory", directory)
  return HttpApiApp.webHandler().handler(
    new Request(`http://localhost${route}`, {
      ...init,
      headers,
    }),
    context,
  )
}

const Event = Schema.Struct({
  id: Schema.String,
  type: Schema.String,
  location: Schema.Struct({
    directory: Schema.String,
    project: Schema.Struct({ id: Schema.String, directory: Schema.String }),
  }),
  data: Schema.Unknown,
})

// A single read() may carry several SSE frames (or part of one) when the runner is
// loaded, so frames are split on the blank-line separator instead of per chunk.
function events(reader: ReadableStreamDefaultReader<Uint8Array>) {
  const decoder = new TextDecoder()
  let buffer = ""
  return async function next() {
    while (true) {
      const end = buffer.indexOf("\n\n")
      if (end !== -1) {
        const frame = buffer.slice(0, end)
        buffer = buffer.slice(end + 2)
        const data = frame
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).replace(/^ /, ""))
          .join("\n")
        if (!data) continue
        return JSON.parse(data) as { type?: unknown }
      }
      const value = await reader.read()
      if (value.done) throw new Error("event stream closed")
      buffer += decoder.decode(value.value, { stream: true })
    }
  }
}

// Only the awaited event is decoded: unrelated events such as file.watcher.updated
// (the watcher may see .git/opencode being written) carry a bare Location.Ref.
async function readEventType(next: ReturnType<typeof events>, type: string) {
  for (let index = 0; index < 20; index++) {
    const event = await next()
    if (event.type === type) return Schema.decodeUnknownSync(Event)(event)
  }
  throw new Error(`timed out waiting for ${type}`)
}

afterEach(async () => {
  await disposeAllInstances()
  await resetDatabase()
})

describe("v2 location HttpApi", () => {
  test("returns command and skill snapshots with resolved locations", async () => {
    await using tmp = await tmpdir({ git: true })

    for (const route of ["/api/command", "/api/skill"]) {
      const response = await request(route, tmp.path)
      expect(response.status).toBe(200)
      const body = (await response.json()) as {
        location: { directory: string; project: { id: string } }
        data: unknown
      }
      expect(body.data).toBeArray()
      expect(body.location.directory).toBe(tmp.path)
      expect(body.location.project.id).toBeTruthy()
    }
  })

  test("streams native EventV2 payloads with resolved locations", async () => {
    await using tmp = await tmpdir({ git: true })
    const response = await request("/api/event", tmp.path)
    const reader = response.body!.getReader()
    const next = events(reader)
    expect((await readEventType(next, "server.connected")).location.directory).toBe(tmp.path)

    const created = await request("/session", tmp.path, { method: "POST" })
    expect(created.status).toBe(200)
    expect(await readEventType(next, "session.created")).toMatchObject({
      type: "session.created",
      location: { directory: tmp.path, project: { directory: tmp.path } },
      data: { sessionID: expect.any(String) },
    })
    await reader.cancel()
  })
})
