# Dinoblog 2.1 — Direct Chat

รุ่น `2.1-chat` เพิ่มการพิมพ์แชทโต้ตอบกลับมา โดยใช้การเชื่อมต่อ Wi-Fi Direct + TCP ของ Phase 2 ผู้ใช้พิมพ์ข้อความเองได้แล้ว ไม่ได้จำกัดแค่ Hello Dinoblog

## สิ่งที่ใช้ได้

- ค้นหาและเชื่อมต่อมือถือ Android สองเครื่องด้วย Wi-Fi Direct
- พิมพ์ข้อความได้ถึง 4,096 UTF-16 code units รองรับภาษาไทย, emoji และหลายบรรทัด
- เห็นข้อความที่ส่งและรับในหน้าสนทนาเดียวกัน แยกบับเบิล **You / Peer** พร้อมเวลา
- กดส่งได้เมื่อ TCP เชื่อมต่อและมีข้อความที่ไม่ใช่ช่องว่าง
- ล้างช่องพิมพ์หลังส่งสำเร็จ เก็บข้อความร่างไว้หากส่งล้มเหลว; draft อยู่ใน ViewModel จึงไม่หายเมื่อสลับแท็บหรือหมุนหน้าจอ
- แสดงประวัติ 100 ข้อความล่าสุดใน memory ของ process และเลื่อนไปข้อความใหม่อัตโนมัติ
- หน้า Debug แสดง IP, server/client role และ TX/RX logs

**Sent** หมายถึงเขียนลง TCP socket สำเร็จ ไม่ใช่การยืนยันว่าอีกเครื่องแสดงผลแล้ว **Received** หมายถึงเครื่องนี้รับข้อความครบแล้ว

ยังคงไม่มี Room, ACK, TTL, offline queue, automatic retry หรือ DTN ประวัติแชทไม่คงอยู่หลัง process ถูกปิด ส่วน Device ID ยังคงอยู่ใน SharedPreferences เหมือนเดิม รุ่นนี้ไม่เปิดหรือลบฐานข้อมูลของรุ่นแรก

## ติดตั้งและทดสอบด้วยมือถือจริง

1. ติดตั้ง `app/build/outputs/apk/debug/app-debug.apk` ทับบนมือถือ **ทั้งสองเครื่อง** ไม่ต้องถอนแอปก่อน หลังเปิดต้องเห็นหัวแอป **DIRECT CHAT · 2.1**
2. เปิด Wi-Fi และ Location ทั้งคู่ อนุญาต Nearby devices บน Android 13+ หรือ Location บน Android 8–12L; Android 12 ให้เลือก Precise location
3. ไม่ต้องเปิด hotspot หรือใช้ Internet วางมือถือใกล้กันและเปิดแอปไว้ด้านหน้าทั้งคู่
4. เข้า **Peers → Scan for peers** ทั้งสองเครื่อง
5. บน A เลือก B แล้วกด **Connect** จากนั้นรับคำขอบน B หาก Android แสดงคำขอ
6. รอ TCP `Connected` บนทั้งสองฝั่ง Android จะเลือกเครื่องหนึ่งเป็น group owner / TCP server โดยอัตโนมัติ
7. หาก client เชื่อมไม่สำเร็จ ให้เปิด Debug ดู role แล้วให้ server แสดง **Listening on 8988** ก่อน จากนั้นกด **Start TCP** บน client
8. กด **Open chat** หรือเข้าแท็บ **Chat** บนทั้งสองเครื่อง
9. A พิมพ์ `สวัสดีครับ 🦖` ในช่อง **Message** แล้วกดปุ่มลูกศรส่ง
10. A ต้องเห็นบับเบิลฝั่งขวา `You · Sent` และ B เห็นข้อความเดียวกันฝั่งซ้าย `Peer · Received`
11. B พิมพ์ตอบกลับ A ต้องเห็นคำตอบทางฝั่งซ้าย และ B เห็นข้อความของตนทางฝั่งขวา
12. ลองส่งข้อความหลายบรรทัดและข้อความเดิมซ้ำ ตรวจว่าทั้งคู่เห็นครบโดยข้อความไม่ถูกแก้ไข

ปุ่มส่งถูกปิดใช้งานขณะไม่เชื่อมต่อ แต่ยังพิมพ์ร่างไว้ได้ หลังเชื่อมใหม่ต้องกดส่งเอง ไม่มีคิวหรือส่งซ้ำอัตโนมัติ หากเปิด settings หรือย้ายแอปไป background ระบบจะปิด socket; เมื่อกลับเข้าแอปจะตรวจกลุ่มเดิมและเริ่ม TCP หนึ่งครั้ง

## Build

- Android Studio ที่รองรับ AGP 8.11.1
- JDK **17**, Gradle 8.13, Kotlin 2.1.20
- Compile SDK 36, target SDK 35, minimum Android 8.0 / API 26
- App version `2.1-chat`, versionCode `3`

ตั้ง Gradle JDK เป็น 17 (ในเครื่องนี้ใช้ `.tools/jdk-17.0.20.1+1`) และแก้ SDK path ใน `local.properties` ให้ตรงกับเครื่อง

```powershell
.\build.ps1
```

สคริปต์จะ build debug APK, รัน unit/socket tests และตรวจ Android lint Internet ใช้เฉพาะดาวน์โหลด dependencies/build tools ไม่จำเป็นขณะใช้แอปบนมือถือ

## โครงสร้างสำคัญ

ทุก package อยู่ใต้ `app/src/main/java/com/group16/dinoblog/`

| ส่วน | ไฟล์ |
|---|---|
| Wi-Fi Direct discovery / group / IP | `network/WifiDirectManager.kt` |
| TCP send/receive และประวัติข้อความใน memory | `network/TcpTransport.kt` |
| UTF-8 framing | `network/TextFrameCodec.kt` |
| ข้อความร่าง, สถานะกำลังส่ง และการส่ง | `viewmodel/AppViewModel.kt` |
| ช่องพิมพ์, ปุ่มส่ง, บับเบิลแชท | `ui/ChatScreen.kt` |
| ประเภทข้อมูล ChatMessage / network state | `model/NetworkState.kt` |

TCP ทำงานบน `Dispatchers.IO`; Compose รับประวัติและสถานะผ่าน `StateFlow` ข้อมูลบนสายยังเป็น length prefix 4 bytes + UTF-8 text จึงไม่มีการเพิ่ม ACK หรือ envelope ใหม่ Device ID แสดงบนหน้า Device แต่ไม่ได้แลกผ่าน TCP ในเวอร์ชันนี้

## Verification

ดูผลล่าสุดใน `VERIFICATION.md`

- Unit/socket test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

Tests บนคอมพิวเตอร์ตรวจการส่ง UTF-8 และประวัติทั้งสองฝั่งได้ แต่ยังต้องยืนยัน Wi-Fi Direct, การแสดง UI และ keyboard บนมือถือจริงสองเครื่อง

## Troubleshooting

- ยังเห็นปุ่ม Send Hello Dinoblog: มือถือยังใช้ APK เดิม ให้ติดตั้ง 2.1 ทับทั้งสองเครื่อง
- ไม่พบ peer: ตรวจ Wi-Fi, Location, permissions แล้ว Scan ทั้งสองเครื่องใหม่
- P2P connected แต่ TCP error: เริ่ม server ให้ Listening ก่อน แล้วกด Start TCP บน client
- ปุ่มส่งกดไม่ได้: ต้องเชื่อม TCP และพิมพ์ข้อความที่ไม่ใช่ช่องว่างก่อน
- ไม่เห็นข้อความอีกฝั่ง: ตรวจ TCP Connected และ TX/RX logs ใน Debug ทั้งคู่
- ประวัติหายหลังระบบปิด process: รุ่นนี้ยังเก็บเฉพาะใน memory ไม่มีฐานข้อมูลข้อความ

โค้ดรุ่นแรกยังอยู่ใน `archive/previous-full-version/`; คู่มือและผลตรวจของ demo ก่อนแก้ไขอยู่ใน `archive/phase2-demo/` ทั้งสองโฟลเดอร์ไม่รวมใน APK
