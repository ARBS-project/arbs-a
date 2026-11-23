# ARBS-A — Android client (APEX Remote Bridge STACKON)

> **Unofficial open-source project.** ARBS is not affiliated with, endorsed by, or sponsored by Flydigi.
> It is also not affiliated with the Moonlight, Sunshine, Apollo or Vibepollo projects.
> All product names and trademarks belong to their respective owners. This repository contains no Flydigi logos or images.

## What is this?

ARBS-A ("ARBS Moonlight") is a fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)
that lets a **Flydigi APEX 6 Pro** controller connected to the Android device (USB) feel the host game's DualSense output
(standard rumble, adaptive-trigger effects and PCM HD haptics) while streaming from an ARBS-H host.

ARBS consists of four repositories in the `arbs-project` organisation:

| Repository | Role |
|---|---|
| `arbs` | Project overview, install and download guide (README), license notices, ARBS Core sources |
| `arbs-h` | Host (fork of Vibepollo 2.18.9) |
| `arbs-w` | Windows client (fork of moonlight-qt) |
| `arbs-a` | Android client (this repository, fork of moonlight-android) |

## Based on

- `moonlight-stream/moonlight-android` commit `b48494cb` plus `Nonary/moonlight-common-c` commit `6f05ee89` vendored into
  `app/src/main/jni/moonlight-core/moonlight-common-c/` (no submodules).
- This repository starts from a single **baseline commit** made from those two sources, so it does **not** contain the
  upstream git history. Details: [NOTICE-ARBS.md](NOTICE-ARBS.md).
- Application ID `io.github.arbs_project.arbsa` (installs next to the official Moonlight app). Java package names (`com.limelight`) are unchanged.
- The original README is kept as [README.upstream.md](README.upstream.md).

## Changes from upstream (summary)

- APEX USB driver and haptics engine (`com.limelight.arbs.*`, `ApexController`): PCM haptics to the grips, rumble, trigger effects.
- ARBS settings screen, ARBS log viewer, crash reporter (logs mask addresses and tokens).
- Unit tests (JUnit, test scope only).

Status: **experimental**. What has actually been confirmed on real devices is listed in the `arbs` repository README.

## Install

No ARBS-A release has been published yet. When one is available on the [Releases](https://github.com/ARBS-project/arbs-a/releases) page:

1. Download `arbs-a-<version>-release.apk` and its `.sha256`, and compare the SHA-256.
2. Open the APK on the Android device and allow installing from this source when Android asks.
3. Pair with the host once using the PIN.
4. Connect the APEX 6 Pro dongle to the device by USB.

This application ID is new, so it installs as a separate app from earlier test builds (`dev.arbs.moonlight`) and needs one new PIN pairing.
The optional update check in Settings (ARBS) is off by default; it only opens the Releases page and never installs anything.

Release signing certificate SHA-256: _to be added after the device check_

## License

GPL-3.0 — see [LICENSE.txt](LICENSE.txt). Upstream and third-party notices: [NOTICE-ARBS.md](NOTICE-ARBS.md).
Security issues: please use the private reporting described in the `arbs` repository `SECURITY.md`.

---

## 한국어 요약

ARBS-A(ARBS Moonlight)는 moonlight-android(`b48494cb`)와 Nonary moonlight-common-c(`6f05ee89`)를 합친 baseline에서 시작한 Android 클라이언트
포크입니다(원본 git 이력 없음). Android 기기에 USB로 연결한 Flydigi APEX 6 Pro에서 호스트 게임의 DualSense 출력(일반 진동·트리거 효과·
PCM HD 햅틱)을 느끼게 합니다. 앱 ID `io.github.arbs_project.arbsa`. 라이선스 GPL-3.0. 상태: 실험적.
**Flydigi와 관련 없는 비공식 오픈소스 프로젝트**이며 Moonlight·Sunshine·Apollo·Vibepollo 프로젝트와도 관련이 없습니다.

### 설치

아직 배포한 ARBS-A 릴리스가 없습니다. [Releases](https://github.com/ARBS-project/arbs-a/releases)에 올라오면:

1. `arbs-a-<버전>-release.apk`와 `.sha256`을 받아 SHA-256을 비교합니다.
2. Android 기기에서 APK를 열고, 묻는 경우 이 출처의 앱 설치를 허용합니다.
3. 호스트와 PIN으로 한 번 페어링합니다.
4. APEX 6 Pro 동글을 기기에 USB로 연결합니다.

새 앱 ID라서 이전 시험판(`dev.arbs.moonlight`)과 별개 앱으로 설치되며 PIN 페어링이 한 번 필요합니다.
설정(ARBS)의 업데이트 확인은 기본 꺼짐이고, Releases 페이지만 열 뿐 자동으로 설치하지 않습니다.

릴리스 서명 인증서 SHA-256: _실기 확인 뒤 기재_
