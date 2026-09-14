// scripts/prepare-harmony.mjs
// 把 platform-agnostic 的 core/*.ts 复制进鸿蒙工程，并按 ArkTS 习惯去掉 import 的 .ts 扩展名。
// 用法： node scripts/prepare-harmony.mjs

import fs from 'node:fs';
import path from 'node:path';

const SRC = 'core';
const DST = path.join('harmony', 'entry', 'src', 'main', 'ets', 'core');

fs.mkdirSync(DST, { recursive: true });

let count = 0;
for (const f of fs.readdirSync(SRC)) {
  if (!f.endsWith('.ts')) continue;
  let s = fs.readFileSync(path.join(SRC, f), 'utf8');
  // './types.ts' -> './types'（ArkTS 解析不带扩展名）
  s = s.replace(/(from\s+')(\.\.?\/[^']*?)\.ts(')/g, '$1$2$3');
  fs.writeFileSync(path.join(DST, f), s);
  count++;
  console.log('copied core/' + f + ' -> ' + DST);
}
console.log('\n完成：' + count + ' 个文件已同步到鸿蒙工程目录');
