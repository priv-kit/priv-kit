import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const fontPath = fileURLToPath(new URL('../src/commonMain/composeResources/font/noto_sans_sc.ttf', import.meta.url));
const fontUrl = 'https://raw.githubusercontent.com/google/fonts/bd8f81ddb5c74d5c8897b36ad88b440266245103/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf';
const expectedHash = 'a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da';
const checksum = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

export async function prepareFont(): Promise<void> {
  try {
    if (checksum(await readFile(fontPath)) === expectedHash) return;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }

  console.log('Downloading pinned Noto Sans SC font...');
  const response = await fetch(fontUrl, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Font download failed: HTTP ${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (checksum(bytes) !== expectedHash) throw new Error('Downloaded font SHA-256 mismatch');
  await mkdir(path.dirname(fontPath), { recursive: true });
  const temporaryPath = `${fontPath}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporaryPath, bytes, { flag: 'wx' });
    await rename(temporaryPath, fontPath);
  } finally {
    await rm(temporaryPath, { force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await prepareFont();
}
