export type WasmAssetMetadata = Readonly<{
  fileName: string;
  byteLength: number;
}>;

export type WasmDownloadProgress = Readonly<{
  loadedBytes: number;
  totalBytes: number;
  ratio: number;
  complete: boolean;
  determinate: boolean;
}>;

type FetchInput = RequestInfo | URL;
type FetchImplementation = (
  input: FetchInput,
  init?: RequestInit,
) => Promise<Response>;

type AssetState = {
  readonly metadata: WasmAssetMetadata;
  loadedBytes: number;
  finished: boolean;
};

export function createTrackedWasmFetch(
  fetchImplementation: FetchImplementation,
  assets: readonly WasmAssetMetadata[],
  onProgress: (progress: WasmDownloadProgress) => void,
): FetchImplementation {
  const assetStates = new Map(
    assets.map((metadata) => [
      metadata.fileName,
      { metadata, loadedBytes: 0, finished: false } satisfies AssetState,
    ]),
  );
  const totalBytes = assets.reduce(
    (total, asset) => total + asset.byteLength,
    0,
  );
  let determinate = totalBytes > 0 && typeof TransformStream !== 'undefined';

  function publishProgress(): void {
    const states = [...assetStates.values()];
    const loadedBytes = states.reduce(
      (total, state) => total + Math.min(state.loadedBytes, state.metadata.byteLength),
      0,
    );
    const complete = states.length > 0 && states.every((state) => state.finished);

    onProgress({
      loadedBytes,
      totalBytes,
      ratio: totalBytes === 0 ? 0 : Math.min(loadedBytes / totalBytes, 1),
      complete,
      determinate,
    });
  }

  publishProgress();

  return async (input, init) => {
    const response = await fetchImplementation(input, init);
    const state = findAssetState(response.url || getInputUrl(input), assetStates);

    if (!state || !response.ok) return response;
    if (!response.body || typeof TransformStream === 'undefined') {
      determinate = false;
      publishProgress();
      return response;
    }

    let requestLoadedBytes = 0;
    const trackedBody = response.body.pipeThrough(
      new TransformStream<Uint8Array, Uint8Array>({
        transform(chunk, controller) {
          requestLoadedBytes += chunk.byteLength;
          state.loadedBytes = Math.max(state.loadedBytes, requestLoadedBytes);
          publishProgress();
          controller.enqueue(chunk);
        },
        flush() {
          state.finished ||= requestLoadedBytes >= state.metadata.byteLength;
          publishProgress();
        },
      }),
    );

    return new Response(trackedBody, {
      headers: response.headers,
      status: response.status,
      statusText: response.statusText,
    });
  };
}

function findAssetState(
  rawUrl: string,
  assetStates: ReadonlyMap<string, AssetState>,
): AssetState | undefined {
  const fileName = getUrlFileName(rawUrl);
  if (!fileName) return undefined;

  for (const state of assetStates.values()) {
    if (matchesAssetFileName(fileName, state.metadata.fileName)) return state;
  }

  return undefined;
}

function getInputUrl(input: FetchInput): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.href;
  return input.url;
}

function getUrlFileName(rawUrl: string): string | undefined {
  try {
    const baseUrl = typeof document === 'undefined'
      ? 'http://localhost/'
      : document.baseURI;
    const pathname = new URL(rawUrl, baseUrl).pathname;
    const encodedFileName = pathname.slice(pathname.lastIndexOf('/') + 1);
    return decodeURIComponent(encodedFileName);
  } catch {
    return undefined;
  }
}

function matchesAssetFileName(
  emittedFileName: string,
  sourceFileName: string,
): boolean {
  if (emittedFileName === sourceFileName) return true;

  const extensionIndex = sourceFileName.lastIndexOf('.');
  if (extensionIndex <= 0) return false;

  const stem = sourceFileName.slice(0, extensionIndex);
  const extension = sourceFileName.slice(extensionIndex);
  return (emittedFileName.startsWith(`${stem}-`) || emittedFileName.startsWith(`${stem}.`))
    && emittedFileName.endsWith(extension);
}
