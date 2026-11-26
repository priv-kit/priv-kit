import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url));
const release = 'llvm-23.1.5-2';
// Standalone builds from https://github.com/cpp-linter/clang-tools-static-binaries.
const binaries: Record<string, { asset: string; sha256: string }> = {
  'win32-x64': {
    asset: 'clang-format-23_windows-amd64.exe',
    sha256: 'c821047edcdc0f9f0128c09a9e85dd2c72ecd8633c8ea0211d544c40870c9454',
  },
  'win32-arm64': {
    asset: 'clang-format-23_windows-arm64.exe',
    sha256: '3643d002a67b01b23832d0a1d4210038eefb461ebf12c9a635fe34d84c30dc7a',
  },
  'linux-x64': {
    asset: 'clang-format-23_linux-amd64',
    sha256: 'edb8e16067855d8ec8c6fe2c2028a5cdd0bd2f0360a974243fc418abe5ef4bd9',
  },
  'linux-arm64': {
    asset: 'clang-format-23_linux-arm64',
    sha256: 'd77832870b33b226297d1fc4d925aa3e085e2bbc0a0fed3a9e0a18fe96834fb2',
  },
  'darwin-x64': {
    asset: 'clang-format-23_macos-amd64',
    sha256: 'ee5974d5f2f0afb54319eff6b304547e8566613418c41362c8b48fc9207a81f8',
  },
  'darwin-arm64': {
    asset: 'clang-format-23_macos-arm64',
    sha256: 'd813d20b122172ae05e19b4748fbd945ba1cbbc71a25d4e59f1de9d592cf4a6c',
  },
};
const checksum = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

async function prepareBinary(): Promise<string> {
  const platform = `${process.platform}-${process.arch}`;
  const binary = binaries[platform];
  if (!binary) throw new Error(`Unsupported clang-format platform: ${platform}`);

  const cacheDirectory = path.join(repositoryRoot, 'node_modules', '.cache', 'clang-format', release);
  const executable = path.join(cacheDirectory, binary.asset);
  try {
    if (checksum(await readFile(executable)) === binary.sha256) return executable;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }

  console.log(`Downloading ${binary.asset} (${release})...`);
  const url = `https://github.com/cpp-linter/clang-tools-static-binaries/releases/download/${release}/${binary.asset}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`clang-format download failed: HTTP ${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (checksum(bytes) !== binary.sha256) throw new Error('Downloaded clang-format SHA-256 mismatch');
  await mkdir(cacheDirectory, { recursive: true });
  await writeFile(executable, bytes, { mode: 0o755 });
  return executable;
}

async function main(): Promise<void> {
  let args = process.argv.slice(2);
  // With arguments, act as the native CLI; without arguments, format repository sources.
  if (args.length === 0) {
    const files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard', '--deduplicate'], {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }).split('\0').filter((file) =>
      /\.(?:c|cc|cpp|cxx|h|hh|hpp|hxx)$/iu.test(file) && existsSync(path.join(repositoryRoot, file)));
    if (files.length === 0) {
      console.log('No C/C++ files to format.');
      return;
    }
    args = ['-i', ...files.map((file) => path.join(repositoryRoot, file))];
    console.log(`Formatting ${files.length} C/C++ files...`);
  }

  const executable = await prepareBinary();
  const result = spawnSync(executable, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}

await main().catch((error: Error) => {
  console.error(error.message);
  process.exitCode = 1;
});
