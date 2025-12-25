const fs = require('fs');
const path = require('path');
const archiver = require('archiver');

const browsers = process.argv[2] ? [process.argv[2]] : ['chrome', 'firefox', 'safari'];
const srcDir = path.join(__dirname, '..', 'src');
const distDir = path.join(__dirname, '..', 'dist');

// Ensure dist directory exists
if (!fs.existsSync(distDir)) {
  fs.mkdirSync(distDir, { recursive: true });
}

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  const entries = fs.readdirSync(src, { withFileTypes: true });

  for (const entry of entries) {
    const srcPath = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDir(srcPath, destPath);
    } else {
      fs.copyFileSync(srcPath, destPath);
    }
  }
}

function adjustManifest(manifest, browser) {
  const adjusted = { ...manifest };

  if (browser === 'firefox') {
    // Firefox requires browser_specific_settings
    adjusted.browser_specific_settings = {
      gecko: {
        id: "dom-repl@example.com",
        strict_min_version: "109.0"
      }
    };
  }

  if (browser === 'safari') {
    // Safari uses the same manifest v3, but may need adjustments
    // The actual Safari extension is built via Xcode from this base
  }

  return adjusted;
}

async function createZip(sourceDir, outPath) {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(outPath);
    const archive = archiver('zip', { zlib: { level: 9 } });

    output.on('close', resolve);
    archive.on('error', reject);

    archive.pipe(output);
    archive.directory(sourceDir, false);
    archive.finalize();
  });
}

async function build(browser) {
  console.log(`Building for ${browser}...`);

  const browserDir = path.join(distDir, browser);

  // Clean and create browser directory
  if (fs.existsSync(browserDir)) {
    fs.rmSync(browserDir, { recursive: true });
  }

  // Copy source files
  copyDir(srcDir, browserDir);

  // Adjust manifest for browser
  const manifestPath = path.join(browserDir, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const adjustedManifest = adjustManifest(manifest, browser);
  fs.writeFileSync(manifestPath, JSON.stringify(adjustedManifest, null, 2));

  // Create zip for distribution
  const zipPath = path.join(distDir, `dom-repl-${browser}.zip`);
  await createZip(browserDir, zipPath);

  console.log(`  Created: ${zipPath}`);
}

async function main() {
  for (const browser of browsers) {
    await build(browser);
  }
  console.log('Build complete!');
}

main().catch(console.error);
