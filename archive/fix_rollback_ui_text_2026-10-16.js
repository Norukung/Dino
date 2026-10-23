const fs = require('fs');
const path = 'app/src/main/java/com/group16/dinoblog/ui/PeerDiscoveryScreen.kt';
let text = fs.readFileSync(path, 'utf8');
text = text.replace(/(selected && socket\.awaitingHello -> ")[^"]*(")/, '$1Verifying identity...$2')
  .replace(/(selected && network\.groupFormed -> ")[^"]*(")/, '$1Verifying identity...$2')
  .replace(/(selected && network\.wifiStatus\.startsWith\("Connecting"\) -> ")[^"]*(")/, '$1Connecting...$2');
fs.writeFileSync(path, text);
