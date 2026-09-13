import { execFile } from 'node:child_process';
import { readFile, readdir } from 'node:fs/promises';
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

interface MarkdownHeading {
  level: number;
  anchor: string;
}

async function readMarkdownHeadings(
  filePath: string,
  displayPath: string,
): Promise<{ headings: MarkdownHeading[]; errors: string[] }> {
  const lines = (await readFile(filePath, 'utf8')).split(/\r?\n/u);
  const headings: MarkdownHeading[] = [];
  const errors: string[] = [];
  const anchors = new Set<string>();
  let fenceMarker: '`' | '~' | null = null;

  lines.forEach((line, index) => {
    const fence = line.match(/^\s*(`{3,}|~{3,})/u)?.[1];
    if (fence) {
      const marker = fence[0] as '`' | '~';
      if (fenceMarker === null) {
        fenceMarker = marker;
      } else if (fenceMarker === marker) {
        fenceMarker = null;
      }
      return;
    }
    if (fenceMarker !== null) return;

    const heading = line.match(/^(#{1,6})[ \t]+/u);
    if (!heading) return;

    const anchoredHeading = line.match(
      /^(#{1,6})[ \t]+.+?[ \t]+\{#([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\}[ \t]*$/u,
    );
    if (!anchoredHeading) {
      errors.push(`${displayPath}:${index + 1} is missing a valid manual heading anchor`);
      return;
    }

    const anchor = anchoredHeading[2];
    if (anchors.has(anchor)) {
      errors.push(`${displayPath}:${index + 1} repeats heading anchor #${anchor}`);
      return;
    }
    anchors.add(anchor);
    headings.push({
      level: anchoredHeading[1].length,
      anchor,
    });
  });

  return { headings, errors };
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

  const headingErrors: string[] = [];
  for (const file of rootFiles) {
    const [english, chinese] = await Promise.all([
      readMarkdownHeadings(path.join(websiteRoot, file), file),
      readMarkdownHeadings(path.join(localizedRoot, file), `zh/${file}`),
    ]);
    headingErrors.push(...english.errors, ...chinese.errors);

    const englishStructure = english.headings.map(({ level, anchor }) => `${level}:${anchor}`);
    const chineseStructure = chinese.headings.map(({ level, anchor }) => `${level}:${anchor}`);
    if (englishStructure.join('\n') !== chineseStructure.join('\n')) {
      headingErrors.push(
        `${file} heading anchors differ between locales:\n` +
          `  English: ${englishStructure.join(', ') || '(none)'}\n` +
          `  Chinese: ${chineseStructure.join(', ') || '(none)'}`,
      );
    }
  }

  if (headingErrors.length) {
    console.error(`Heading anchor validation failed:\n${headingErrors.map((error) => `  - ${error}`).join('\n')}`);
    process.exitCode = 1;
  } else {
    console.log(`Heading anchors verified for ${rootFiles.length} document pairs.`);
  }
}
