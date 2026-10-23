# Dinoblog — Phase 2: Wi-Fi Direct + TCP

แอป Android สำหรับทดสอบการส่งข้อความ UTF-8 **Hello Dinoblog** ระหว่างมือถือสองเครื่องที่เชื่อม Wi-Fi Direct แล้ว

รุ่นนี้มีเฉพาะการค้นหา/เชื่อมต่อ Wi-Fi Direct, TCP socket, ปุ่มส่งข้อความตัวอย่าง และการแสดงข้อความที่รับ ไม่ใช้ Room, ACK, TTL, automatic retry, offline queue หรือ DTN

โค้ดรุ่นก่อนที่มีฟังก์ชันเพิ่มเติมถูกเก็บไว้ใน `archive/previous-full-version/` ซึ่งอยู่นอก Android source sets และไม่รวมใน APK ของ Phase 2 ข้อมูลในฐานข้อมูลของแอปรุ่นเดิมไม่ได้ถูกลบ แต่รุ่นนี้ไม่เปิดหรือใช้ฐานข้อมูลนั้น

## Build

- Android Studio ที่รองรับ AGP 8.11.1
- Gradle 8.13, Kotlin 2.1.20, **JDK 17**
- Compile SDK 36, target SDK 35, minimum Android 8.0 / API 26
- App version `2.0-phase2`, versionCode `2`

เปิดโฟลเดอร์นี้ใน Android Studio และตั้ง Gradle JDK เป็น 17 ในเครื่องนี้ใช้ `.tools/jdk-17.0.20.1+1` ได้ ตรวจ `local.properties` ให้ชี้ SDK ของเครื่องที่ใช้งาน

```powershell
# Build APK, run host-side tests, run Android lint
.\build.ps1

# Force every task to execute again
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', '--rerun-tasks')
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Internet จำเป็นสำหรับดาวน์โหลด build tools/dependencies ครั้งแรกเท่านั้น การใช้งานแอปบนมือถือไม่ต้องมี Internet หรือ router

## วิธีทดสอบ A → B ด้วยมือถือจริง

1. ติดตั้ง **Phase 2 APK เดียวกันบนทั้งสองเครื่อง** อย่าใช้ APK รุ่นก่อนกับ Phase 2 เพราะ protocol ต่างกัน หลังติดตั้งต้องเห็นหัวแอป **PHASE 2 · DIRECT TCP**
2. เปิด Wi-Fi และ Location ทั้งสองเครื่อง ปิด hotspot หากต้องการพิสูจน์ว่า offline ให้ปิด mobile data และตัดการเชื่อมต่อ Wi-Fi Internet แต่เปิด Wi-Fi radio ไว้
3. เปิด Dinoblog ทั้งสองเครื่อง อนุญาต Nearby devices บน Android 13+ หรือ Location บน Android 8–12L โดย Android 12 ต้องเลือก Precise location
4. วางเครื่องใกล้กัน เปิดแอปค้างด้านหน้าและอย่าให้หน้าจอดับ
5. เข้า **Peers → Scan for peers** ทั้งสองเครื่อง
6. บน A เลือกชื่อ B แล้วกด **Connect**; รับคำขอบน B หาก Android แสดงคำขอ ไม่ต้องกด Connect พร้อมกันสองฝั่ง
7. เข้า **Debug** และตรวจว่า Wi-Fi Direct เป็น `P2P group connected` Android จะเลือกเครื่องหนึ่งเป็น `Group owner / TCP server` อีกเครื่องเป็น `Group client / TCP client` A ไม่จำเป็นต้องเป็น server
8. เมื่อกลุ่มเกิดขึ้น แอปจะพยายามเปิด TCP หนึ่งครั้งบนแต่ละเครื่อง รอจนทั้งสองแสดง TCP `Connected`
9. หาก TCP client แสดง error เพราะ server ยังไม่พร้อม ให้ดูเครื่อง server ว่าแสดง `Listening on 8988` ก่อน หากยังไม่ฟังให้กด **Start TCP** บน server จากนั้นกด **Start TCP** บน client การเริ่มใหม่เป็นการกดของผู้ใช้ ไม่มีการ retry อัตโนมัติ
10. เปิดแท็บ **Chat** บนทั้งสองเครื่อง จะเห็นหน้า **TCP demo**
11. กด **Send Hello Dinoblog** บน A
12. บน B ใต้ **Received text** ต้องแสดงข้อความ **Hello Dinoblog** ตรงตามนี้
13. เปิด Debug: A ต้องมี `TX: Hello Dinoblog`; B ต้องมี `RX: Hello Dinoblog` โดยไม่มี ACK packet
14. สามารถกดส่งจาก B กลับ A เพื่อทดสอบว่า A ส่งได้ไม่ว่าจะเป็น group owner หรือ client

ปุ่มส่งจะกดไม่ได้ขณะ TCP ยังไม่ connected ข้อความแจ้งฝั่งส่งยืนยันเพียงว่าเขียนลง socket สำเร็จ ให้ตรวจผลรับจริงที่หน้าจอ B

## ทดสอบการตัดการเชื่อมต่อ

1. กด Disconnect บนหน้า Peers
2. ตรวจว่าปุ่มส่งถูกปิดใช้งาน ไม่มีข้อความถูกเก็บรอส่ง
3. เชื่อม Wi-Fi Direct ใหม่ แล้วทำขั้นตอน TCP อีกครั้ง ไม่มีการนำข้อความเก่ามาส่งซ้ำ

หากปิดแอปไป background จะปิด socket และหยุด discovery เมื่อกลับเข้าแอปจะตรวจกลุ่ม Wi-Fi Direct ที่ยังอยู่และเริ่ม TCP หนึ่งครั้ง หากจำเป็นให้กด Start TCP ด้วยตนเอง

## การทำงานและไฟล์สำคัญ

| ส่วน | ไฟล์ |
|---|---|
| Discover / connect / group-owner IP | `network/WifiDirectManager.kt` |
| TCP server/client และ receive loop | `network/TcpTransport.kt` |
| UTF-8 framing | `network/TextFrameCodec.kt` |
| ปุ่มส่งและ UI state | `viewmodel/AppViewModel.kt` |
| ปุ่ม Send Hello Dinoblog และข้อความที่รับ | `ui/ChatScreen.kt` |
| IP, role, TX/RX logs | `ui/NetworkDebugScreen.kt` |
| Runtime permissions | `util/Permissions.kt`, `ui/DinoblogApp.kt` |

ไฟล์ Kotlin อยู่ใต้ `app/src/main/java/com/group16/dinoblog/` MainActivity จัดการแค่ lifecycle และ Compose entry; networking ทำงานบน `Dispatchers.IO` และส่งผลให้ Compose ผ่าน `StateFlow`

- TCP server bind กับ **group-owner IP ที่ Android แจ้ง** ที่ port **8988** ไม่ hardcode IP
- TCP client ใช้ IP/port นั้นและลอง connect ครั้งเดียว โดยมี connect timeout 5 วินาที
- Socket เดียวรับส่งได้ทั้งสองทิศทาง ไม่มี JSON envelope, HELLO handshake, ACK หรือ heartbeat
- ข้อมูลหนึ่งข้อความ = **ความยาว 4 bytes แบบ big-endian + ข้อความ UTF-8 ตามความยาวนั้น** เช่น Hello Dinoblog มี payload 14 bytes
- TCP แบ่ง/รวมข้อมูลระหว่างทางได้ จึงใช้ `readFully()` เพื่อรับครบหนึ่งข้อความ รองรับภาษาไทย, emoji และ newline
- จำกัดข้อความ 64 KiB ต่อ frame; ตรวจ UTF-8 ที่ไม่ถูกต้อง
- Received text และ logs เก็บใน memory สูงสุดอย่างละ 100 รายการ ไม่ persist เมื่อ process สิ้นสุด
- Device ID ของ Phase 1 ยังคงอยู่ใน SharedPreferences แต่ไม่ได้ใช้เป็น address หรือแลกผ่าน TCP ใน Phase 2
- ปิด actual sockets เมื่อหยุด connection เพื่อปลด blocking `accept()` / `read()`

## Permissions

`AndroidManifest.xml` มี `INTERNET` สำหรับ local TCP sockets, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE` และ:

- Android 13+: `NEARBY_WIFI_DEVICES` พร้อม `neverForLocation`
- Android 8–12L: `ACCESS_FINE_LOCATION` และ `ACCESS_COARSE_LOCATION`, จำกัดถึง API 32

แอปตรวจว่าเปิด Location ก่อนเริ่ม scan สิทธิ์ INTERNET ไม่ได้แปลว่าต้องมีอินเทอร์เน็ต

## ผลทดสอบ

ดูผลล่าสุดใน `VERIFICATION.md` และรายงาน:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

Tests ใช้ socket จริงบน localhost เพื่อตรวจ UTF-8, การรับข้อมูล, การไม่ตอบ ACK, ไม่ queue ขณะ offline และไม่ retry อัตโนมัติ ผลบนคอมพิวเตอร์ไม่ยืนยันการเชื่อมต่อ Wi-Fi Direct ของฮาร์ดแวร์มือถือ ต้องทำตาม checklist สองเครื่องด้านบน

## Troubleshooting

| อาการ | วิธีตรวจ |
|---|---|
| ยังเห็น TTL / Recipient Device ID | ยังเป็น APK รุ่นก่อน ให้ติดตั้ง Phase 2 APK ใหม่ |
| ไม่เห็น peer | เปิด Wi-Fi, Location, permissions และแอปทั้งสองฝั่ง แล้ว scan ใหม่ |
| Permission ถูกปฏิเสธ | Device → Refresh / permissions หรือ Android app settings |
| P2P connected แต่ TCP error | ให้ server Listening ก่อน แล้วกด Start TCP บน client |
| ฝั่ง B ไม่เห็นข้อความ | ตรวจ TCP Connected ทั้งคู่, APK ต้องเป็น Phase 2 ทั้งคู่, ดู TX/RX ใน Debug |
| Group ถูกตัด | กลับไป Peers แล้วเชื่อมใหม่ |
| Build error เกี่ยวกับ Java 25/26 | ตั้ง Gradle JDK เป็น 17 |
