import { execFile } from 'node:child_process';
import { lstat, readFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export async function collectFontCharacters(repositoryRoot: string): Promise<string> {
  const listFiles = async (options: string[]) => {
    const { stdout } = await execFileAsync('git', ['ls-files', '-z', ...options], {
      cwd: repositoryRoot,
      encoding: 'utf8',
      maxBuffer: 16 * 1024 * 1024,
    });
    return stdout.split('\0').filter(Boolean);
  };
  const [files, ignoredTrackedFiles] = await Promise.all([
    listFiles(['--cached', '--others', '--exclude-standard']),
    // Git normally keeps tracked files even when they match an ignore rule.
    listFiles(['--cached', '--ignored', '--exclude-standard']),
  ]);
  const ignored = new Set(ignoredTrackedFiles);
  const characters = new Set(Array.from({ length: 128 }, (_, code) => String.fromCodePoint(code)));
  for (const file of new Set(files)) {
    if (ignored.has(file) || !/\.(kt|xml)$/.test(file)) continue;
    const sourcePath = path.join(repositoryRoot, file);
    const info = await lstat(sourcePath).catch((error: NodeJS.ErrnoException) => {
      if (error.code === 'ENOENT') return undefined;
      throw error;
    });
    if (!info?.isFile()) continue;
    const nonAscii = (await readFile(sourcePath, 'utf8')).replace(/[\u0000-\u007f]+/g, '');
    for (const character of new Set(nonAscii)) characters.add(character);
  }
  return [...characters].sort((a, b) => a.codePointAt(0)! - b.codePointAt(0)!).join('');
}
