// Patches TransformControls.js from ES module to use global THREE
const fs = require('fs');
const path = require('path');

const filePath = path.join(__dirname, '..', 'service', 'public', 'TransformControls.js');
let code = fs.readFileSync(filePath, 'utf8');

// Remove the ES module import block (from 'import {' to "from 'three';")
const importEnd = code.indexOf("from 'three'");
if (importEnd < 0) { console.log('No import found, already patched'); process.exit(0); }
const afterImportStmt = code.indexOf(';', importEnd) + 1;
code = code.substring(afterImportStmt).trim();

// Remove the ES module export block
const exportStart = code.lastIndexOf('export {');
if (exportStart >= 0) {
  const exportEnd = code.indexOf('}', exportStart) + 1;
  code = code.substring(0, exportStart) + code.substring(exportEnd).trim();
}

// Add global THREE references at the top
const classes = [
  'BoxGeometry', 'BufferGeometry', 'CylinderGeometry', 'DoubleSide', 'Euler',
  'Float32BufferAttribute', 'Line', 'LineBasicMaterial', 'Matrix4', 'Mesh',
  'MeshBasicMaterial', 'Object3D', 'OctahedronGeometry', 'PlaneGeometry',
  'Raycaster', 'ShaderChunk', 'ShaderLib', 'ShaderMaterial', 'SphereGeometry', 'Vector3'
];

const header = classes.map(c => `const ${c} = THREE.${c};`).join('\n') + '\n';
code = header + '\n' + code;

fs.writeFileSync(filePath, code);
console.log(`Patched TransformControls.js: ${code.length} bytes`);
