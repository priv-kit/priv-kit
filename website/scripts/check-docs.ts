import { execFile } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const websiteRoot = path.resolve(scriptDirectory, '..');
const repositoryRoot = path.resolve(websiteRoot, '..');
const localizedRoot = path.join(websiteRoot, 'zh');
const excludedRootDirectories = new Set([
  '.vitepress',
  'node_modules',
  'public',
  'scripts',
  'zh',
]);
const execFileAsync = promisify(execFile);

async function collectMarkdownFiles(
  directory: string,
  relativeTo: string,
  excludedDirectories: ReadonlySet<string> = new Set(),
): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);

    if (entry.isDirectory()) {
      if (directory === relativeTo && excludedDirectories.has(entry.name)) {
        continue;
      }

      files.push(...(await collectMarkdownFiles(entryPath, relativeTo)));
      continue;
    }

    if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(path.relative(relativeTo, entryPath).split(path.sep).join('/'));
    }
  }

  return files;
}

const { stdout: repositoryFileOutput } = await execFileAsync(
  'git',
  ['ls-files', '--cached', '--others', '--exclude-standard'],
  {
    cwd: repositoryRoot,
    encoding: 'utf8',
  },
);
const disallowedScriptFiles = repositoryFileOutput
  .split(/\r?\n/u)
  .filter((file) => /\.(?:js|jsx|mjs|mjsx|cjs|cjsx|mts|cts)$/iu.test(file));

if (disallowedScriptFiles.length) {
  console.error(
    `Script files must use the .ts extension:\n${disallowedScriptFiles
      .map((file) => `  - ${file}`)
      .join('\n')}`,
  );
  process.exitCode = 1;
} else {
  console.log('TypeScript source extension policy verified.');
}

const rootFiles = await collectMarkdownFiles(
  websiteRoot,
  websiteRoot,
  excludedRootDirectories,
);
const localizedFiles = await collectMarkdownFiles(localizedRoot, localizedRoot);

const rootSet = new Set(rootFiles);
const localizedSet = new Set(localizedFiles);
const missingInChinese = rootFiles.filter((file) => !localizedSet.has(file));
const missingInEnglish = localizedFiles.filter((file) => !rootSet.has(file));

if (missingInChinese.length || missingInEnglish.length) {
  if (missingInChinese.length) {
    console.error(
      `Missing Chinese documents:\n${missingInChinese.map((file) => `  - ${file}`).join('\n')}`,
    );
  }

  if (missingInEnglish.length) {
    console.error(
      `Missing English documents:\n${missingInEnglish.map((file) => `  - ${file}`).join('\n')}`,
    );
  }

  process.exitCode = 1;
} else {
  console.log(`Locale parity verified for ${rootFiles.length} document pairs.`);
}
