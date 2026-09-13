/// <reference types="node" />

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createTrackedWasmFetch,
  type WasmAssetMetadata,
  type WasmDownloadProgress,
} from './wasmDownloadProgress.ts';

const assets = [
  { fileName: 'priv-playground.wasm', byteLength: 5 },
  { fileName: 'skiko.wasm', byteLength: 3 },
] satisfies readonly WasmAssetMetadata[];

test('aggregates concurrent hashed Wasm downloads without changing their bytes', async () => {
  const updates: WasmDownloadProgress[] = [];
  const responses = new Map<string, readonly number[]>([
    ['priv-playground-Dph5cnjQ.wasm', [2, 3]],
    ['skiko.DuWZud6c.wasm', [1, 2]],
  ]);
  const trackedFetch = createTrackedWasmFetch(
    async (input) => {
      const url = input.toString();
      const fileName = url.slice(url.lastIndexOf('/') + 1);
      const chunks = responses.get(fileName);
      assert.ok(chunks);
      return chunkedResponse(chunks);
    },
    assets,
    (progress) => updates.push(progress),
  );

  const [playgroundResponse, skikoResponse] = await Promise.all([
    trackedFetch('https://example.test/assets/priv-playground-Dph5cnjQ.wasm'),
    trackedFetch('https://example.test/assets/skiko.DuWZud6c.wasm'),
  ]);
  const [playgroundBytes, skikoBytes] = await Promise.all([
    playgroundResponse.arrayBuffer(),
    skikoResponse.arrayBuffer(),
  ]);

  assert.equal(playgroundBytes.byteLength, 5);
  assert.equal(skikoBytes.byteLength, 3);
  assert.equal(playgroundResponse.headers.get('content-type'), 'application/wasm');
  assert.deepEqual(updates.at(-1), {
    loadedBytes: 8,
    totalBytes: 8,
    ratio: 1,
    complete: true,
    determinate: true,
  });
  assert.ok(
    updates.every((update, index) =>
      index === 0 || update.loadedBytes >= updates[index - 1]!.loadedBytes),
  );
});

test('uses the largest attempt for a retried asset instead of double counting it', async () => {
  const updates: WasmDownloadProgress[] = [];
  let attempt = 0;
  const trackedFetch = createTrackedWasmFetch(
    async () => chunkedResponse(attempt++ === 0 ? [2] : [2, 2]),
    [{ fileName: 'runtime.wasm', byteLength: 4 }],
    (progress) => updates.push(progress),
  );

  await (await trackedFetch('/runtime.wasm')).arrayBuffer();
  await (await trackedFetch('/runtime-abc123.wasm')).arrayBuffer();

  assert.deepEqual(updates.at(-1), {
    loadedBytes: 4,
    totalBytes: 4,
    ratio: 1,
    complete: true,
    determinate: true,
  });
});

test('passes unrelated responses through unchanged', async () => {
  const originalResponse = new Response('ok');
  const trackedFetch = createTrackedWasmFetch(
    async () => originalResponse,
    assets,
    () => undefined,
  );

  assert.equal(await trackedFetch('/index.js'), originalResponse);
});

test('falls back to indeterminate progress when a Wasm response has no body', async () => {
  const updates: WasmDownloadProgress[] = [];
  const originalResponse = new Response(null, { status: 204 });
  const trackedFetch = createTrackedWasmFetch(
    async () => originalResponse,
    assets,
    (progress) => updates.push(progress),
  );

  assert.equal(await trackedFetch('/skiko.wasm'), originalResponse);
  assert.equal(updates.at(-1)?.determinate, false);
});

function chunkedResponse(chunkLengths: readonly number[]): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const length of chunkLengths) {
          controller.enqueue(new Uint8Array(length));
        }
        controller.close();
      },
    }),
    {
      headers: { 'content-type': 'application/wasm' },
      status: 200,
    },
  );
}
