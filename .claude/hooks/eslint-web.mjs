// PostToolUse(Write|Edit) — modern/web 의 .ts/.tsx 를 고치면 그 파일만 eslint 로 검사한다.
// 위반이 있으면 exit 2 로 결과를 Claude 에게 돌려준다(편집은 이미 적용된 상태).
// eslint 가 설치돼 있지 않으면(npm ci 전) 조용히 넘어간다.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const input = JSON.parse(readFileSync(0, 'utf8') || '{}');
const file = input.tool_input?.file_path ?? input.tool_response?.filePath;
const webDir = path.resolve('modern/web');

if (!file || !/\.tsx?$/.test(file)) process.exit(0);
const abs = path.resolve(file);
if (!abs.startsWith(webDir + path.sep) || abs.includes(`${path.sep}node_modules${path.sep}`)) process.exit(0);

const eslint = path.join(webDir, 'node_modules', '.bin', 'eslint');
if (!existsSync(eslint) || !existsSync(abs)) process.exit(0);

const result = spawnSync(eslint, [path.relative(webDir, abs)], { cwd: webDir, encoding: 'utf8' });
if (result.status === 0) process.exit(0);

process.stderr.write(`eslint 위반 (${path.relative(process.cwd(), abs)}):\n${result.stdout}${result.stderr}`);
process.exit(2);
