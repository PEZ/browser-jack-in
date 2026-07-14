import chokidar from 'chokidar';
import { spawn } from 'node:child_process';

let running = false;
let pending = false;

function compile() {
  if (running) {
    pending = true;
    return;
  }
  running = true;
  console.log('[test-watch] squint compile --paths src test --output-dir build/test');
  const child = spawn(
    'npx',
    ['squint', 'compile', '--paths', 'src', 'test', '--output-dir', 'build/test'],
    { stdio: 'inherit', shell: true }
  );
  child.on('exit', (code) => {
    running = false;
    if (code !== 0) {
      console.error(`[test-watch] compile exited with ${code}`);
    }
    if (pending) {
      pending = false;
      compile();
    }
  });
}

console.log('[test-watch] watching src/ and test/ (full recompile on .cljs/.cljc change)');
chokidar
  .watch(['src', 'test'], {
    ignored: /(^|[\\/])\../,
    ignoreInitial: true,
    awaitWriteFinish: { stabilityThreshold: 200, pollInterval: 50 }
  })
  .on('all', (_event, path) => {
    if (/\.(cljs|cljc)$/.test(path)) {
      console.log(`[test-watch] ${_event} ${path}`);
      compile();
    }
  });
