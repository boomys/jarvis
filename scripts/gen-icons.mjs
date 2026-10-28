// PWA / launcher ikonlarını (PNG) bağımlılık olmadan üretir.
import zlib from "node:zlib";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const outDir = path.join(root, "public", "icons");
fs.mkdirSync(outDir, { recursive: true });

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

function encodePNG(size, pixels) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    const rowStart = y * (size * 4 + 1);
    raw[rowStart] = 0;
    pixels.copy(raw, rowStart + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));

// Kırmızı arc-reactor + "J" monogramı
function draw(size, { maskable = false } = {}) {
  const px = Buffer.alloc(size * size * 4);
  const c = size / 2;
  const scale = maskable ? 0.72 : 1; // maskable güvenli alan
  const ringR = 0.62 * c * scale;
  const ringW = size * 0.035;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = x - c;
      const dy = y - c;
      const r = Math.hypot(dx, dy);
      const i = (y * size + x) * 4;

      // arka plan
      let R = 10 + 16 * Math.max(0, 1 - r / c);
      let G = 4 + 4 * Math.max(0, 1 - r / c);
      let B = 7 + 6 * Math.max(0, 1 - r / c);

      // dış halka (parlak kırmızı)
      const ringDist = Math.abs(r - ringR);
      if (ringDist < ringW) {
        const k = 1 - ringDist / ringW;
        R = R + (255 - R) * k;
        G = G + (43 - G) * k;
        B = B + (61 - B) * k;
      }

      // iç çekirdek parlaması
      const coreR = 0.34 * c * scale;
      if (r < coreR) {
        const k = Math.pow(1 - r / coreR, 1.6);
        R = R + (200 - R) * k;
        G = G + (25 - G) * k;
        B = B + (40 - B) * k;
      }

      // ince çizgili çember (teknolojik detay)
      const tick = Math.abs(r - 0.78 * c * scale) < size * 0.006;
      if (tick && Math.floor(Math.atan2(dy, dx) * 16) % 2 === 0) {
        R = 255;
        G = 120;
        B = 130;
      }

      // "J" monogramı (beyaz-kırmızı)
      const nx = (x - c) / (c * scale);
      const ny = (y - c) / (c * scale);
      const stem = nx > 0.02 && nx < 0.16 && ny > -0.34 && ny < 0.16;
      const hook =
        Math.abs(Math.hypot(nx + 0.13, ny - 0.16) - 0.13) < 0.07 && ny > 0.12 && nx < -0.02;
      if (stem || hook) {
        R = 255;
        G = 238;
        B = 241;
      }

      px[i] = clamp(R);
      px[i + 1] = clamp(G);
      px[i + 2] = clamp(B);
      px[i + 3] = 255;
    }
  }
  return px;
}

const targets = [
  { file: "icon-192.png", size: 192, maskable: false },
  { file: "icon-512.png", size: 512, maskable: false },
  { file: "icon-maskable-512.png", size: 512, maskable: true },
];

for (const t of targets) {
  const png = encodePNG(t.size, draw(t.size, { maskable: t.maskable }));
  fs.writeFileSync(path.join(outDir, t.file), png);
  console.log(`${t.file} — ${(png.length / 1024).toFixed(1)} KB`);
}
