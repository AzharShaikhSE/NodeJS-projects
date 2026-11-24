// Serve the APK on your Wi-Fi so the Fire TV "Downloader" app can grab it with
// a short URL instead of the long GitHub one.
//   npm run share-apk   ->  type  http://192.168.x.x:8000/a  into Downloader
import http from 'node:http';
import os from 'node:os';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const apkPath = path.join(here, '../dist/NamasteCast.apk');
const port = Number(process.env.PORT || 8000);
const apk = await readFile(apkPath);

const ips = Object.values(os.networkInterfaces()).flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);

http.createServer((req, res) => {
  if (req.url === '/a' || req.url === '/NamasteCast.apk') {
    console.log(`[apk] ${req.socket.remoteAddress} downloading (${apk.length} bytes)`);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="NamasteCast.apk"',
      'Content-Length': apk.length,
    });
    return res.end(apk);
  }
  res.writeHead(302, { Location: '/a' }); res.end();
}).listen(port, () => {
  console.log('In the Fire TV Downloader app, type one of:');
  for (const ip of ips) console.log(`  http://${ip}:${port}/a`);
  console.log('Ctrl+C when the TV has it.');
});
