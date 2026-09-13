import { spawn } from 'node:child_process';
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { init, parse } from 'es-module-lexer';
import subsetFont from 'subset-font';
import { collectFontCharacters } from './font-characters.ts';

const playgroundRoot = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const repositoryRoot = path.resolve(playgroundRoot, '..');
const playgroundBuild = path.join(playgroundRoot, 'build');
const destination = path.resolve(playgroundRoot, 'dist');
const windows = process.platform === 'win32';
const wrapper = path.join(repositoryRoot, windows ? 'gradlew.bat' : 'gradlew');
const tasks = [
  ':priv-playground:compileProductionExecutableKotlinWasmJs',
  ':priv-playground:processSkikoRuntimeForKWasm',
  ':priv-playground:wasmJsProcessResources',
];

await new Promise<void>((resolve, reject) => {
  const child = spawn(
    windows ? (process.env.ComSpec ?? 'cmd.exe') : wrapper,
    windows ? ['/d', '/s', '/c', `""${wrapper}" ${tasks.join(' ')}"`] : tasks,
    { cwd: repositoryRoot, stdio: 'inherit', windowsVerbatimArguments: windows },
  );
  child.on('error', reject);
  child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`Wasm build exited with ${code}`)));
});

if (path.dirname(destination) !== playgroundRoot || path.basename(destination) !== 'dist') {
  throw new Error(`Unexpected playground output directory: ${destination}`);
}
await rm(destination, { recursive: true, force: true });
await mkdir(destination, { recursive: true });
await cp(
  path.join(playgroundBuild, 'compileSync', 'wasmJs', 'main', 'productionExecutable', 'kotlin'),
  destination,
  { recursive: true },
);
for (const file of ['skiko.mjs', 'skiko.wasm']) {
  await cp(path.join(playgroundBuild, 'compose', 'skiko-runtime-processed-wasmjs', file), path.join(destination, file));
}
let fontCopied = false;
await cp(path.join(playgroundBuild, 'processedResources', 'wasmJs', 'main'), destination, {
  recursive: true,
  async filter(source, target) {
    if (path.basename(source) !== 'noto_sans_sc.ttf' || path.basename(path.dirname(source)) !== 'font') return true;
    const characters = await collectFontCharacters(repositoryRoot);
    const originalFont = await readFile(source);
    const subset = await subsetFont(originalFont, characters, { targetFormat: 'sfnt' });
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, subset);
    fontCopied = true;
    console.log(`Playground font: ${originalFont.length} → ${subset.length} bytes (${[...characters].length} requested code points)`);
    // The transformed font is already written; skip copying the full source font.
    return false;
  },
});
if (!fontCopied) throw new Error('Missing playground font');

// Keep Kotlin's optional Node/Deno branches out of the browser bundle.
await init;
for (const file of await readdir(destination)) {
  if (!file.endsWith('.mjs')) continue;
  const filePath = path.join(destination, file);
  let source = await readFile(filePath, 'utf8');
  const [imports] = parse(source);
  for (const item of [...imports].reverse()) {
    if (!item.n?.startsWith('node:') && !item.n?.startsWith('https:')) continue;
    if (item.d < 0) throw new Error(`Unexpected non-browser static import in ${file}: ${item.n}`);
    source = `${source.slice(0, item.s)}/* @vite-ignore */ ${source.slice(item.s)}`;
  }
  await writeFile(filePath, source);
}

// Literal URLs let Vite fingerprint and relocate every Compose resource alongside the Wasm assets.
const resourceRoot = path.join(destination, 'composeResources');
const resourceFiles = (await readdir(resourceRoot, { recursive: true, withFileTypes: true }))
  .filter((entry) => entry.isFile())
  .map((entry) => path.relative(destination, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'))
  .sort();
const resourceEntries = resourceFiles.map((file) =>
  `  ${JSON.stringify(file)}: new URL(${JSON.stringify(`./${file}`)}, import.meta.url).href,`);
await writeFile(path.join(destination, 'index.mjs'), [
  "import { renderPrivilegePlayground as render } from './priv-playground.mjs';",
  'const resources = {',
  ...resourceEntries,
  '};',
  'export function renderPrivilegePlayground(container, dark, useLegacyPackaging) {',
  '  return render(container, (path) => {',
  '    if (!Object.hasOwn(resources, path)) throw new Error(`Unknown Compose resource: ${path}`);',
  '    return resources[path];',
  '  }, dark, useLegacyPackaging);',
  '}',
  '',
].join('\n'));
await writeFile(path.join(destination, 'index.d.mts'),
  'export declare function renderPrivilegePlayground(container: HTMLElement, dark: boolean, useLegacyPackaging: boolean): (dark: boolean, useLegacyPackaging: boolean) => void;\n');

for (const file of ['priv-playground.mjs', 'priv-playground.wasm', 'skiko.mjs', 'skiko.wasm']) {
  if (!(await stat(path.join(destination, file))).isFile()) throw new Error(`Missing playground asset: ${file}`);
}
const wasmAssets = await Promise.all(['priv-playground.wasm', 'skiko.wasm'].map(async (fileName) => ({
  fileName,
  byteLength: (await stat(path.join(destination, fileName))).size,
})));
await writeFile(path.join(destination, 'wasm-assets.mjs'), `export const wasmAssets = ${JSON.stringify(wasmAssets)};\n`);
await writeFile(path.join(destination, 'wasm-assets.d.mts'),
  'export declare const wasmAssets: readonly Readonly<{ fileName: string; byteLength: number }>[];\n');
if ((await readdir(path.join(destination, 'composeResources'))).length === 0) {
  throw new Error('Missing Compose resources');
}
