# Dinoblog 2.8 - End-to-end Delivery Receipts (Phase 6)

Current version: **2.8-delivery-receipts**, versionCode **11**.
Build with `.\build.ps1`. For Android/Room tests on the attached phone, run
`.\test-device.ps1` from normal desktop PowerShell; see [device setup and the ADB profile fix](DEVICE_TESTING.md).
[Phase 6 architecture, wire format and exact multi-phone demo](PHASE6_DELIVERY_RECEIPTS.md) ·
[Build/test evidence](VERIFICATION.md).

The final destination now atomically stores a persistent delivery receipt alongside
its received message. Receipts travel through future HELLO/INVENTORY encounters,
independently of the original route: **message A -> B -> C -> D; receipt D -> E -> B -> A**.
The source becomes DELIVERED. Every informed node stops forwarding the original
payload and keeps its historical copy. Per-hop ACK/retry and Phase 5 routing remain.

Install `app/build/outputs/apk/debug/app-debug.apk` on all demo phones. Room schema
**3** adds one receipt table through migration **2 -> 3**; existing messages/peers are
preserved. Receipt IDs share the existing INVENTORY packet with separate message IDs.
No external server, global replica deletion or advanced routing algorithm is added.

## Historical Phase 5 notes

The no-receipt restriction below is superseded by Phase 6 above.

# Dinoblog 2.7 - Epidemic Routing (Phase 5)

Current version: **2.7-epidemic-routing**, versionCode **10**.
[Phase 5 implementation, protocol flow and four-phone tests](PHASE5_EPIDEMIC.md) ·
[Current build/test results](VERIFICATION.md).

Messages now travel through disconnected encounters **A -> B -> C -> D**.
After HELLO and INVENTORY, a peer missing an eligible message can receive a copy
even when it is not the destination. Direct delivery takes priority. Each carrier
retains its persistent copy and its original branch depth. Relay ACK confirms
storage only; the final receiver uses the existing RECEIVED state and sends a
DELIVERED ACK. UUID, author and destination remain unchanged throughout the route.

Install `app/build/outputs/apk/debug/app-debug.apk` on all four phones. Room schema
2, wire protocol 2, Wi-Fi Direct, TCP, ACK/retry and existing UI flows are retained.
No external server, Internet or simultaneous end-to-end connection is required.
No backward delivery receipts or global replica cleanup are implemented.

## Historical Phase 4 notes

The direct-only forwarding restriction below is superseded by Phase 5 above.

# Dinoblog 2.6 - Store-Carry-Forward (Phase 4)

Current version: **2.6-store-carry-forward**, versionCode **9**.
[Phase 4 implementation and exact three-phone demo](PHASE4_FORWARDING.md) ·
[Current build/test results](VERIFICATION.md).

A sends a message for C through connected carrier B. After A disconnects, B can
automatically deliver the original UUID to C following HELLO and INVENTORY comparison.
Only the final destination is eligible; no arbitrary relay flooding or receipt
propagation to A. Received IDs are advertised to prevent resends after a lost ACK.

Install `app/build/outputs/apk/debug/app-debug.apk` on all three phones. Expect
**STORE-CARRY-FORWARD · 2.6**. Existing direct chat, custody ACK/retry, Room history,
schema version 2 and wire protocol version 2 are retained.

## Historical Phase 3 notes

The following describes the earlier inventory-only milestone. Its no-forwarding
instructions are superseded by Phase 4 above.

# Dinoblog 2.5 - DTN Inventory (Phase 3)

Current version: **2.5-inventory**, versionCode **8**. Start with
[Phase 3 inventory architecture and two-phone tests](PHASE3_INVENTORY.md) and
[current verification results](VERIFICATION.md).

After HELLO, peers exchange eligible message UUIDs and display local/remote inventories
and missing IDs in Network Debug. Inventory performs no forwarding or Room writes.
Existing direct chat, explicit first-hop carrier sends, ACKs and retries remain.
Install `app/build/outputs/apk/debug/app-debug.apk` on both phones and expect
`DTN INVENTORY · 2.5`. Room schema and wire protocol remain version 2.

## Historical 2.4 notes

The section below describes the earlier HELLO milestone, not the current app.

# Dinoblog 2.4 - Peer Identity / HELLO

Current version: **2.4-peer-identity**, versionCode **7**.

Direct chat is preserved. The new milestone lets A explicitly send a message for C to connected carrier B. B saves it in Room as `CARRIED`; after custody ACK A shows `FORWARDED`, not `DELIVERED`. Forwarding from B to C is intentionally disabled.

**Start here: [Phase 2 HELLO, architecture and exact two-phone test steps](PHASE2_HELLO.md).** Both phones automatically exchange persistent UUIDs when TCP connects. Debug distinguishes **Waiting for HELLO...**, an identified peer UUID, and **None** after disconnect. Carried messages are NOT forwarded. [Store-Carry tests](STORE_CARRY.md) remain available for regression testing with the current APK.

- Install `app/build/outputs/apk/debug/app-debug.apk` on both phones; expect `PEER IDENTITY · 2.4` in the app header.
- New in 2.4: explicit HELLO state/logs and session-safe identity clearing, plus a `packetType` field alongside legacy `type`. No Phase 3 forwarding.
- New in 2.3.1: strict new-storage admission (`hopCount < hopLimit` and `now < expiresAt`), self-contained `DTN RECEIVE` logs, and acceptance of non-destination first-hop bundles without an optional carrier hint. Normal direct chat and existing lost-ACK recovery remain.
- Build/check with `./build.ps1`; see [executed verification](VERIFICATION.md).
- Room schema is now **2**, with an additive v1 -> v2 migration preserving existing history. Install over the old app; do not clear storage.
- Wire protocol is now **2**; both devices need the current APK.
- Wi-Fi Direct discovery/connection, Room, DeliveryEngine, Internet requirements and Android permissions are unchanged. The existing TCP transport was extended for handshake state/logging, not replaced.

## Historical 2.2 notes (superseded where different)

The original documentation below is retained for reference. Its version numbers, protocol, schema and direct-only scope describe **2.2**, not the current app. Use `STORE_CARRY.md` above for current behavior and testing.

# Dinoblog 2.2 - Offline Chat (historical)

แอป Android สำหรับ Introduction to Computer Networking, Group 16 เวอร์ชัน `2.2-offline-chat` คืนระบบ Room, ACK, TTL, PENDING queue และ retry พร้อมคงหน้าพิมพ์แชทของรุ่น 2.1

รองรับมือถือ Android **A ↔ B** ผ่าน Wi-Fi Direct + TCP โดยไม่ใช้อินเทอร์เน็ต ไม่มี multi-hop, Internet backend, login หรือโหมด PC/hotspot

## ฟังก์ชันที่คืนมา

- พิมพ์ข้อความไทย, emoji และหลายบรรทัดได้สูงสุด 4,096 UTF-16 code units
- แสดงบับเบิลข้อความส่ง/รับ พร้อมเวลา, message ID แบบย่อ, TTL และสถานะ
- Room บันทึกทั้งข้อความส่งและรับ ประวัติไม่หายเมื่อปิดแอปหรือ restart process
- ถ้าอีกเครื่อง offline ข้อความจะบันทึกเป็น **PENDING** แล้วส่งซ้ำเมื่อ peer ที่ถูกต้องเชื่อมต่อกลับมา
- UUID เดิมถูกใช้ทุกครั้งที่ retry ผู้รับใช้ primary key ของ Room ป้องกันแถวซ้ำ
- ผู้รับส่ง **ACK หลังบันทึกลง Room สำเร็จ** ผู้ส่งเปลี่ยนเป็น **DELIVERED** เมื่อ ACK มาจาก peer และ messageId ที่ถูกต้อง
- ถ้า ACK หาย ผู้รับจะตอบ ACK อีกครั้งเมื่อเห็นข้อความซ้ำ โดยไม่เพิ่มข้อความอีกแถว
- TTL คืออายุข้อความเป็นวินาทีจาก timestamp เริ่มต้น **86400 = 24 ชั่วโมง** แก้ไขได้ 1–604800 วินาที
- ข้อความหมดอายุเป็น **EXPIRED** และหยุดส่งซ้ำ แต่เก็บไว้ในประวัติ
- จดจำ peer ที่เคยเชื่อมต่อและผู้รับล่าสุด เลือกจาก **Choose peer** หรือรายการ Conversations ในหน้า Device ได้
- แต่ละ peer มี draft แยกกันใน ViewModel; ล้าง draft เมื่อบันทึกข้อความสำเร็จเท่านั้น

| สถานะ | ความหมาย |
|---|---|
| PENDING | บันทึกในเครื่องแล้ว ยังไม่ได้ ACK จากปลายทาง |
| DELIVERED | ปลายทางบันทึกสำเร็จและตอบ ACK แล้ว ไม่ได้หมายความว่าผู้ใช้อ่านแล้ว |
| RECEIVED | ข้อความที่เครื่องนี้รับและบันทึกแล้ว |
| EXPIRED | หมดอายุ หยุดพยายามส่ง |

หาก ACK มาช้าหลัง sender เปลี่ยนเป็น EXPIRED แต่ receiver บันทึกสำเร็จไปแล้ว ACK นั้นยังยืนยัน DELIVERED ได้

## ติดตั้ง / อัปเดต

ไฟล์ APK: `app/build/outputs/apk/debug/app-debug.apk`

1. ติดตั้ง APK 2.2 **ทับทั้งสองเครื่อง** โดยไม่ถอนแอปหรือ Clear storage
2. หลังเปิดให้ตรวจว่าหัวแอปเป็น **OFFLINE CHAT · 2.2**
3. ต้องอัปเดตทั้งคู่ เพราะรุ่น 2.0/2.1 ใช้ protocol ข้อความล้วน แต่ 2.2 ใช้ HELLO/MESSAGE/ACK

ใช้ applicationId, Device ID preferences, ชื่อฐานข้อมูล `dinoblog.db` และ schema version 1 เดิม หากเคยใช้รุ่นแรกและไม่ได้ล้างข้อมูล ประวัติใน Room เดิมสามารถเปิดต่อได้ ไม่มี destructive migration

ข้อความจากรุ่น 2.0/2.1 เก็บเฉพาะ memory จึงไม่สามารถกู้ประวัติที่หายไปเมื่อ process สิ้นสุด ข้อความใหม่ใน 2.2 จะถูกเก็บลง Room

## ทดสอบด้วยมือถือจริงสองเครื่อง

### เชื่อมต่อและคุยโต้ตอบ

1. ใช้ Android 8.0 / API 26 ขึ้นไปสองเครื่องที่รองรับ Wi-Fi Direct
2. เปิด Wi-Fi และ Location ทั้งคู่ ปิด hotspot หากต้องการยืนยัน offline ให้ปิด mobile data และไม่ต่อ Wi-Fi Internet แต่เปิด Wi-Fi radio ไว้
3. เปิด Dinoblog และอนุญาต Nearby devices บน Android 13+ หรือ Location บน Android 8–12L โดย Android 12 ให้เลือก Precise location
4. ตั้งวันเวลาทั้งสองเครื่องให้ตรงกันเพื่อใช้ TTL; วางเครื่องใกล้กันและเปิดแอปไว้ด้านหน้าทั้งคู่
5. เข้า **Peers → Scan for peers** ทั้งสองเครื่อง
6. A เลือก B แล้วกด **Connect** จากฝั่งเดียว รับคำขอบน B หาก Android แสดงคำขอ
7. รอ Wi-Fi Direct เป็น `P2P group connected` และ TCP เป็น `Connected` ทั้งสองเครื่องจะแลก Device ID ผ่าน HELLO อัตโนมัติ
8. เข้า **Open chat** จากหน้า Peers เพื่อเลือก peer ที่กำลังเชื่อมต่อ หรือเลือกผ่าน **Chat → Choose peer**
9. A พิมพ์ `สวัสดีครับ 🦖` และกดส่ง A จะเห็น PENDING แล้ว DELIVERED; B เห็นข้อความหนึ่งรายการเป็น RECEIVED
10. B พิมพ์ตอบกลับ ตรวจประวัติทั้งสองฝั่ง
11. เปิด Debug: ฝั่งส่งมี TX MESSAGE → RX ACK → DB DELIVERED ฝั่งรับมี RX MESSAGE → DB RECEIVED → TX ACK โดย messageId ตรงกัน

Device ID ไม่ใช่ Wi-Fi Direct address หรือ IP ถ้าเลือกผู้รับอื่นที่ไม่ได้เชื่อมต่อ ข้อความจะค้าง PENDING ให้ผู้รับนั้น โดยไม่ส่งผ่านเครื่องที่เชื่อมต่ออยู่

### คิวออฟไลน์ + restart + reconnect

1. หลังเคยเชื่อมต่อแล้ว กด **Disconnect**
2. บน A เลือก B จาก Choose peer หรือ Conversations แล้วส่ง 3 ข้อความ ต้องแสดง PENDING แม้ไม่มี TCP connection
3. ปิดและเปิดแอป A ใหม่ ตรวจว่า Device ID, ผู้รับล่าสุดและข้อความยังอยู่
4. ให้ A/B scan และ connect กันอีกครั้ง
5. หลัง HELLO คิวของ B จะถูกส่งอัตโนมัติ โดยไม่ต้องกดส่งใหม่ B เห็นข้อความละหนึ่งรายการ A เปลี่ยนเป็น DELIVERED หลัง ACK

TCP reconnect เองขณะที่กลุ่ม Wi-Fi Direct ยังอยู่ หากกลุ่มหาย ผู้ใช้ต้อง scan/connect เพื่อสร้างกลุ่มใหม่ก่อน รุ่นนี้ไม่ได้จับคู่ Wi-Fi Direct ใหม่ให้เอง

### TTL

1. ตัดการเชื่อมต่อ เลือก B แล้วแตะ **TTL: …s · edit** ตั้งเป็น `10`
2. ส่งข้อความและรออย่างน้อย 15 วินาทีในแอป สถานะต้องเปลี่ยนเป็น EXPIRED
3. เชื่อม B กลับมา ข้อความหมดอายุจะไม่ถูกส่ง
4. ตั้ง TTL กลับเป็น `86400` แล้วทดสอบข้อความใหม่

### Duplicate และการเก็บข้อมูล

- ส่งข้อความแล้วตัด Wi-Fi ระหว่างทาง จากนั้นต่อใหม่ ตรวจว่าไม่มีแถวข้อความซ้ำ
- การตัด ACK ด้วยมือขึ้นกับจังหวะเครือข่าย; automated test จำลอง ACK แรกหายและตรวจว่าการ retry ใช้ UUID เดิมและมีแถวเดียว
- การปิดแอป/หมุนหน้าจอไม่เปลี่ยน Device ID; Clear storage หรือ uninstall จะลบข้อมูลและเปลี่ยน ID เมื่อติดตั้งใหม่

## Build และ verification

- Android Studio ที่รองรับ AGP 8.11.1
- **JDK 17**, Gradle 8.13, Kotlin 2.1.20, Compose, Room 2.7.2
- Compile SDK 36, target SDK 35, minimum SDK 26
- Version `2.2-offline-chat`, versionCode `4`

เลือก Gradle JDK 17; เครื่องที่สร้างโปรเจกต์มี `.tools/jdk-17.0.20.1+1` และตรวจ SDK path ใน `local.properties`

```powershell
# APK, host tests, lint, and compile Android instrumentation tests
.\build.ps1

# Run real SQLite/Room tests when an Android device/emulator is attached
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest')
```

Internet ใช้เฉพาะการดาวน์โหลด build tools/dependencies ไม่จำเป็นขณะส่งข้อความ

รายงาน: `VERIFICATION.md`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`

## สถาปัตยกรรม

โค้ดอยู่ใต้ `app/src/main/java/com/group16/dinoblog/`

| Layer | หน้าที่ |
|---|---|
| `ui` | Device, Peers, Chat, Debug, Compose theme |
| `viewmodel` | Draft แยกผู้รับ, TTL input, UI state และ user actions |
| `model` | Message พร้อม UUID/sender/receiver/payload/timestamp/ttl/status, peer/network models |
| `network` | Wi-Fi Direct, TCP, HELLO/MESSAGE/ACK และ UTF-8 JSON framing |
| `dtn` | บันทึกก่อนส่ง, pending retry, TTL, duplicate suppression, ACK |
| `database` | Room database, DAO, converters |
| `repository` | MessageStore interface และ Room implementation |
| `util` | Device ID, permissions, network logger |

Group owner เปิด server ที่ P2P IP:8988 อีกเครื่องใช้ owner IP จาก Android เป็น client ไม่มีการ hardcode IP ข้อมูล TCP ใช้ length prefix 4 bytes + UTF-8 JSON เพื่อรองรับ packet fragmentation และ Unicode

คิว retry ทุก 5 วินาทีและเมื่อ peer กลับมา; TCP client retry ทุก 2 วินาทีขณะที่กลุ่มยังอยู่ Heartbeat ทุก 10 วินาทีและ read timeout 35 วินาทีช่วยตรวจการขาดการเชื่อมต่อ

## ข้อจำกัดและ troubleshooting

- เปิดแอปด้านหน้าทั้งสองเครื่องเพื่อรับส่ง ไม่มี background networking service
- ACK ยืนยันการบันทึก ไม่ใช่ read receipt; HELLO ระบุ Device ID แต่ไม่ใช่ authentication ไม่มี TLS/end-to-end encryption ใน protocol รุ่นนี้
- ไม่มี multi-hop, cloud, Internet chat, hotspot mode หรือ PC client
- ประวัติทั้งหมดถูกอ่านผ่าน Room Flow รุ่นนี้ยังไม่มี pagination/retention cleanup
- ไม่เห็น peer: ตรวจ Wi-Fi, Location, permission แล้ว Refresh / permissions และ scan ใหม่
- TCP ไม่ connected: ตรวจว่า APK เป็น 2.2 ทั้งคู่ ดู role/owner IP และ errors ใน Debug; TCP จะ retry หรือกด Start TCP เพื่อเริ่มใหม่ได้
- PENDING ค้าง: ตรวจ receiver Device ID ให้ตรงกับ peer, TTL และ RX ACK ใน Debug
- ส่งไม่ได้: ตรวจ UUID ผู้รับ, TTL 1–604800 และข้อความที่ไม่ว่าง
- ประวัติเดิมหายหลัง uninstall/Clear storage: ไม่มี server หรือ backup ให้กู้คืน

ไฟล์อ้างอิงจากเวอร์ชันก่อนอยู่ใน `archive/` และไม่รวมใน APK
