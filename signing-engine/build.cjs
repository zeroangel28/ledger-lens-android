const { rollup } = require('rollup');
const { nodeResolve } = require('@rollup/plugin-node-resolve');
const commonjs = require('@rollup/plugin-commonjs');
const json = require('@rollup/plugin-json');
const path = require('path');
const fs = require('fs');
const polyfill = require('rollup-plugin-polyfill-node');
const { minify } = require('terser');
const wasmLocal = {
  name: 'bundled-offline-wasm',
  load(id) {
    if (!id.endsWith('.wasm')) return null;
    const content = fs.readFileSync(id);
    const module = new WebAssembly.Module(content);
    const exports = WebAssembly.Module.exports(module).map(x => `export const ${x.name}=instance.exports.${x.name};`).join('\n');
    return `import {Buffer} from 'buffer'; import * as bindings from './cardano_serialization_lib_bg.js';
      const data=Buffer.from('${content.toString('base64')}','base64');
      const instance=new WebAssembly.Instance(new WebAssembly.Module(data), {'./cardano_serialization_lib_bg.js':bindings});\n${exports}`;
  }
};
(async () => {
  for (const browser of (process.argv.includes('--node-only') ? [false] : [false, true])) {
    const bundle = await rollup({ input: path.join(__dirname, 'engine.js'), plugins: [wasmLocal, json(), nodeResolve({ browser, preferBuiltins: !browser }), commonjs(), ...(browser ? [polyfill()] : [])],
      onwarn: warning => { if (warning.code === 'UNRESOLVED_IMPORT') throw new Error(warning.message); if (!['CIRCULAR_DEPENDENCY','THIS_IS_UNDEFINED'].includes(warning.code)) console.warn(warning.message); } });
    const output = browser ? '../app/src/main/assets/signing-engine.js' : 'build/engine.cjs';
    fs.mkdirSync(path.dirname(path.join(__dirname, output)), { recursive: true });
    await bundle.write({ file: path.join(__dirname, output), format: browser ? 'iife' : 'cjs', name: 'LedgerLensCodec',
      intro: browser ? 'globalThis.process={env:{NODE_ENV:"production"},browser:true,nextTick:f=>queueMicrotask(f)};' : '', sourcemap: false });
    await bundle.close();
    if (browser) {
      const target = path.join(__dirname, output);
      const result = await minify(fs.readFileSync(target, 'utf8'), { compress: false, mangle: true, format: { comments: /^!/ } });
      fs.writeFileSync(target, result.code);
    }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
