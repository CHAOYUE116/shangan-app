import { createRequire } from 'node:module';
import fs from 'node:fs';
const require = createRequire('D:/Projects/上岸APP/kaogong-shuati/package.json');
const sharp = require('sharp');

const RES = 'D:/Projects/上岸APP/app-shangan/android/app/src/main/res/';
const SIZES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

const full = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#1B2B40"/><stop offset="1" stop-color="#41627F"/>
  </linearGradient></defs>
  <rect width="512" height="512" rx="112" fill="url(#bg)"/>
  <circle cx="256" cy="178" r="58" fill="#EFA93D"/>
  <circle cx="256" cy="178" r="76" fill="#EFA93D" opacity=".13"/>
  <path d="M256 96v-16M256 274v16M174 178h-16M338 178h16" stroke="#F4C56A" stroke-width="9" stroke-linecap="round"/>
  <path d="M92 366 244 328v126L92 442Z" fill="#F6EFE2"/>
  <path d="M268 328 420 366v76L268 454Z" fill="#E4D5BC"/>
  <path d="M244 328 256 336 268 328v126l-12-8-12 8Z" fill="#D9C9AE"/>
  <path d="M118 386h102M118 410h86" stroke="#D9C9AE" stroke-width="5" stroke-linecap="round" opacity=".8"/>
  <path d="M292 386h102M308 410h86" stroke="#C9B79A" stroke-width="5" stroke-linecap="round" opacity=".8"/>
  <path d="M92 442 244 442 256 450 268 442 420 442v18H92Z" fill="#D9C9AE" opacity=".5"/>
</svg>`;

const foreground = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <circle cx="256" cy="256" r="190" fill="#1B2B40"/>
  <circle cx="256" cy="178" r="58" fill="#EFA93D"/>
  <circle cx="256" cy="178" r="76" fill="#EFA93D" opacity=".13"/>
  <path d="M256 96v-16M256 274v16M174 178h-16M338 178h16" stroke="#F4C56A" stroke-width="9" stroke-linecap="round"/>
  <path d="M92 366 244 328v126L92 442Z" fill="#F6EFE2"/>
  <path d="M268 328 420 366v76L268 454Z" fill="#E4D5BC"/>
  <path d="M244 328 256 336 268 328v126l-12-8-12 8Z" fill="#D9C9AE"/>
  <path d="M118 386h102M118 410h86" stroke="#D9C9AE" stroke-width="5" stroke-linecap="round" opacity=".8"/>
  <path d="M292 386h102M308 410h86" stroke="#C9B79A" stroke-width="5" stroke-linecap="round" opacity=".8"/>
  <path d="M92 442 244 442 256 450 268 442 420 442v18H92Z" fill="#D9C9AE" opacity=".5"/>
</svg>`;

for (const [dpi, size] of Object.entries(SIZES)) {
  const dir = RES + `mipmap-${dpi}/`;
  for (const name of ['ic_launcher.png', 'ic_launcher_round.png']) {
    await sharp(Buffer.from(full)).resize(size, size).png().toFile(dir + name);
  }
  await sharp(Buffer.from(foreground)).resize(size, size).png().toFile(dir + 'ic_launcher_foreground.png');
}
fs.writeFileSync(RES + 'values/ic_launcher_background.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1B2B40</color>
</resources>
`);
console.log('icons generated');

// Android 13+ 主题图标：单色层，避免系统把彩色前景和背景混成低对比
const monochrome = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <circle cx="256" cy="178" r="58" fill="#FFFFFF"/>
  <path d="M256 96v-16M256 274v16M174 178h-16M338 178h16" stroke="#FFFFFF" stroke-width="9" stroke-linecap="round"/>
  <path d="M92 366 244 328v126L92 442Z" fill="#FFFFFF"/>
  <path d="M268 328 420 366v76L268 454Z" fill="#FFFFFF"/>
  <path d="M92 442 244 442 256 450 268 442 420 442v18H92Z" fill="#FFFFFF"/>
</svg>`;
for (const [dpi, size] of Object.entries(SIZES)) {
  await sharp(Buffer.from(monochrome)).resize(size, size).png().toFile(RES + `mipmap-${dpi}/ic_launcher_monochrome.png`);
}
