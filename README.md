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
| `arbs` | Project overview, user guide, download notes, license notices, ARBS Core sources |
| `arbs-h` | Host (fork of Vibepollo 2.18.9) |
| `arbs-w` | Windows client (fork of moonlight-qt) |
| `arbs-a` | Android client (this repository, fork of moonlight-android) |

## Based on

- `moonlight-stream/moonlight-android` commit `b48494cb` plus `Nonary/moonlight-common-c` commit `6f05ee89` vendored into
  `app/src/main/jni/moonlight-core/moonlight-common-c/` (no submodules).
- This repository starts from a single **baseline commit** made from those two sources, so it does **not** contain the
  upstream git history. Details: [NOTICE-ARBS.md](NOTICE-ARBS.md).
- Application ID `dev.arbs.moonlight` (installs next to the official Moonlight app). Java package names (`com.limelight`) are unchanged.
- The original README is kept as [README.upstream.md](README.upstream.md).

## Changes from upstream (summary)

- APEX USB driver and haptics engine (`com.limelight.arbs.*`, `ApexController`): PCM haptics to the grips, rumble, trigger effects.
- ARBS settings screen, ARBS log viewer, crash reporter (logs mask addresses and tokens).
- Unit tests (JUnit, test scope only).

Status: **experimental**. What has actually been confirmed on real devices is listed in the `arbs` repository README.

## License

GPL-3.0 — see [LICENSE.txt](LICENSE.txt). Upstream and third-party notices: [NOTICE-ARBS.md](NOTICE-ARBS.md).
Security issues: please use the private reporting described in the `arbs` repository `SECURITY.md`.

---

## 한국어 요약

ARBS-A(ARBS Moonlight)는 moonlight-android(`b48494cb`)와 Nonary moonlight-common-c(`6f05ee89`)를 합친 baseline에서 시작한 Android 클라이언트
포크입니다(원본 git 이력 없음). Android 기기에 USB로 연결한 Flydigi APEX 6 Pro에서 호스트 게임의 DualSense 출력(일반 진동·트리거 효과·
PCM HD 햅틱)을 느끼게 합니다. 앱 ID `dev.arbs.moonlight`. 라이선스 GPL-3.0. 상태: 실험적.
**Flydigi와 관련 없는 비공식 오픈소스 프로젝트**이며 Moonlight·Sunshine·Apollo·Vibepollo 프로젝트와도 관련이 없습니다.
