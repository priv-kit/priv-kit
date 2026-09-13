import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { promisify } from 'node:util';
import { collectFontCharacters } from './font-characters.ts';

const execFileAsync = promisify(execFile);

test('collects ASCII and source characters while honoring Git ignore rules', async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'priv-font-characters-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const git = (...args: string[]) => execFileAsync('git', args, { cwd: root });
  const write = (file: string, text: string) => writeFile(path.join(root, file), text);
  await git('init', '--quiet');
  await write('tracked.kt', '授');
  await write('ignored-tracked.kt', '忽');
  await write('deleted.kt', '删');
  await git('add', '.');
  await rm(path.join(root, 'deleted.kt'));
  await write('.gitignore', 'ignored-*.kt\n/build/\n');
  await write('ignored-new.kt', '略');
  await mkdir(path.join(root, 'build'));
  await write('build/generated.xml', '禁');
  await mkdir(path.join(root, 'nested'));
  await write('nested/.gitignore', '*.xml\n!keep.xml\n');
  await write('nested/drop.xml', '藏');
  await write('nested/keep.xml', '留');
  await write('新 文件.kt', '权𠮷授');
  await write('ascii.kt', 'package sample\nfun main() = println("ASCII only")\u007f');
  await write('mixed.kt', 'val text = "\u0080é授权𠮷授权𠮷"');
  await write('strings.xml', '<string>连接</string>');
  await write('other.kts', '错');
  await write('notes.md', '误');

  const characters = await collectFontCharacters(root);
  const expected = new Set([
    ...Array.from({ length: 128 }, (_, code) => String.fromCodePoint(code)),
    ...'授权𠮷连接留\u0080é',
  ]);
  assert.deepEqual(new Set(characters), expected);
  assert.equal([...characters].length, expected.size);
  assert.equal(characters, [...expected].sort((a, b) => a.codePointAt(0)! - b.codePointAt(0)!).join(''));
});
