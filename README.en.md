# Genton AI OS — An Open AI-Native Mobile Operating System Base

> **Etymology**: The name Genton AI OS comes from **Genius Tonality** — in an AI-native operating system, agents, the system, users, and other services need a harmonious and ordered "tonality" of collaboration, just as different musical voices compose a complete work under a unified tonality.
>
> **Version**: v0.3-dev (Android Deep Customization Track)　**Status**: Core modules open-sourced, under active development
>
> **Mission**: A **privacy-audit-based trusted cooperation contract layer** for model vendors, hardware vendors, and software service providers. It solves the structural dilemma of "entrance ownership vs. development dominance separation" in agent-native operating systems, enabling cross-party trust-based collaboration.
>
> [中文版](README.md)

---

## Design Philosophy: Trustworthy Cooperation Under Multi-Party Security

Agent-native operating systems face a structural contradiction: **user entrance ownership and development dominance are deeply coupled**. Model vendors, hardware vendors, and software service providers each have their core assets and entrance aspirations, yet deep collaboration often stalls due to unclear trust boundaries.

Genton AI OS's core approach: **not a new entrance overlord, but a "contract layer" trusted by all parties** — with built-in privacy audit, capability tokens, policy engine, and verifiable audit chains at the OS level, agents and services from different entities can collaborate on the same device, while all parties can be confident that:

- Their model weights and core capabilities cannot be extracted by other parties;
- The flow and usage scope of user data are auditable and traceable;
- Capabilities and permissions are granted minimally per task, without overreach;
- Value allocation can be settled based on transparent audit records.

This is what we call the **"privacy-audit-based trusted cooperation contract"**.

### Four Core Mechanisms of the Contract Layer

| Mechanism | Description |
|---|---|
| **Policy as Code** | All collaboration rules are written into the system via policy DSL, validated at compile time, enforced at runtime, and cannot be unilaterally altered by any party |
| **Capability Tokenization** | Permissions are not "permanently granted" but issued as capability tokens per task, per time window — trackable and revocable |
| **Verifiable Audit** | All cross-entity calls are recorded in a hash-chain + TEE-anchored audit log, tamper-proof and independently verifiable, forming the objective basis for cooperation settlement and liability determination |
| **Privacy as Boundary** | Data flows on a minimum-necessary principle; PII (Personally Identifiable Information) is automatically masked; sensitive computation runs in isolated domains — achieving "usable but invisible" |

### Product Paradigm: The Device as a Repeater, Not a Terminal

The device becomes a **repeater** between humans and the digital world: upstream it translates user intent into execution relays, downstream it delivers execution results end-to-end back to the user for acceptance. Apps recede to the background as capability suppliers, and users are no longer locked in the operation process.

Precisely because the device is a repeater, the flow, storage, and manipulation of data **must be secure and auditable** — only then can trust be established among **models, devices, apps, and users**, between different vendors, and between humans and AI, enabling the system to evolve continuously and benignly.

```
Old model:  User ──operate──▶ Phone (terminal) ──call──▶ Apps / Services
                        ↑ User locked in the process

New model:  User (expresses outcome) ──▶ Phone (repeater) ──▶ Digital world (execution)
               ◀────────────── end-to-end result delivery ◀──────────────┘
                              ↑
                   Four-party trust: model / device / app / user
                   = Policy as Code + Capability Tokens + Verifiable Audit
```

### Interaction Paradigm: HUI (Humanistic UI) — Native AI Interaction Based on Continuously Learnable Decision Models

Genton AI OS not only defines a trustworthy contract base but also proposes a new interaction paradigm for AI-native hardware: **HUI (Humanistic UI)**. It is not "adding a chat box on top of GUI" but redefines human-device interaction centered on **continuously learnable decision models**:

| Interaction Dimension | Traditional GUI | HUI |
|---|---|---|
| **Core Input Channels** | Touch + icon navigation | Natural language + posture gestures + context awareness |
| **System Response** | User explicitly triggers, system passively executes | System autonomously plans and executes after understanding intent; user accepts the result |
| **Proactivity** | Passively waiting for user commands | Proactive anticipation based on local decision models (notification sorting, schedule prep, anti-harassment, etc.), always interruptible by the user |
| **Learning Mode** | User manual settings and categorization | On-device continuous learning based on decision models, with auditable and rollback processes |
| **Authorization Mode** | Permission pop-ups (one-time consent) | Capability tokens + traceable audit chain + revocable at any time |
| **System Morphology** | Fixed single mode | Dual-mode one-click switching — AI assistant mode ↔ regular app mode |

Six pillars of HUI interaction:

1. **Conversational main interface**: The default entry is an AI assistant mode conversational interface, delivering all system capabilities through natural language dialogue. Traditional app mode is a one-click fallback alternative with zero learning curve.
2. **Suggestion Capsule**: Contextual anticipations based on local decision models, delivered as capsule-style suggestions (reply drafts, schedule reminders, action suggestions) at appropriate moments — non-intrusive, non-mandatory, user can accept or ignore.
3. **Visual confirmation flow**: Sensitive operations (sending messages, changing system settings, payments, etc.) are exclusively presented by a system-level confirmation panel (FLAG_SECURE, anti-screenshot). The user can clearly confirm the operator, action content, and impact scope before execution proceeds. Confirmation results are called back directly to the execution mediator, not through the Agent runtime (hereafter "the brain"), preventing hijacking.
4. **Activity indicator**: System-wide visible Agent activity indication — users can always perceive when the Agent is reading the screen, executing actions, or capturing audio. The privacy dashboard provides a complete audit timeline and chain integrity verification entry.
5. **Posture interaction (S7)**: Physical gestures as a supplementary input channel — shake-to-ready for camera Q&A, flip-face-down to record a memo. Sensor context is exclusively consumed by the system MotionHub service, not readable by third parties. Disabled by default, force-disabled in sensitive scenarios, with <0.5% daily power increase.
6. **L0.5 form direct connection**: High-frequency deterministic tasks bypass model planning and go through schema-derived forms + LLM-free deterministic pipelines + semantic direct connection, with end-to-end latency from submit to visible ≤100ms (P95) as a design target from the architecture spec — not yet benchmarked on real hardware. The system continuously learns which tasks can converge to one-click direct access.

HUI design principle: **Proactive without overreach, helpful without disturbance.** All proactive capabilities follow the principle of "conservative by default, one-click full-off, transparent learning process" — the decision model only outputs suggestions; the final decision always rests with the policy engine (deterministic rules) and the user.

### Technical Path: Build the Trust Base First, Then the Collaboration Ecosystem

Trust is the premise of cooperation and of agent autonomy. Genton AI OS's technical path is not "build strength first, add security later" — it establishes the trusted base first, then gradually expands collaboration capabilities:

```
Phase 1  Trust Base (system-level, upfront)
        ┌───────────────────────────────────┐
        │ Policy as Code: rules in system, enforced │
        │ Capability Tokenization: minimal per task  │
        │ Verifiable Audit: hash chain + TEE anchor  │
        │ Privacy as Boundary: usable but invisible  │
        └───────────────────────────────────┘
                    │ (within this boundary)
                    ▼
Phase 2  Multi-Party Collaboration & Intelligent Evolution
        ┌───────────────────────────────────┐
        │ Multi-Agent cross-entity collaboration  │
        │ Model / hardware / service plug-and-play │
        │ Audit-based value allocation & settlement│
        │ Continuous learning within trust bounds  │
        └───────────────────────────────────┘
                    │
                    ▼
   More trust → smoother collaboration → more vibrant ecosystem
   → better user experience → win-win value for all parties
```

### Five Design Principles

| Principle | Meaning |
|---|---|
| **AI-First** | The inference engine is a system-level resident capability, not an accessory feature of some app |
| **Intent-Driven** | Users and Agents express goals; the system parses into Goal DAG → policy engine validation → deterministic executor execution |
| **Multi-Party Security** | Model, hardware, service, and user each have their authority; no single party can overstep; the system is a neutral contract executor |
| **Built-in Audit & Verifiability** | Policy as code + hash-chain audit + TEE anchoring; all cooperative behaviors independently verifiable |
| **Deterministic Execution Guarantee** | AI output is probabilistic, but **execution of system side effects must be deterministic** — all system actions go through the "intent bus → policy engine → deterministic executor → audit" pipeline, preventing model output from directly causing system side effects |

---

## Technical Architecture

### Overall Architecture: Dual-Track, Shared Contracts

Genton AI OS is not yet another closed agent OS — it is an **open contract layer** that defines the rules and boundaries for four parties (models, hardware, services, users) to collaborate on a single device. All parties plug in by contract and settle by audit.

We adopt a "dual-track" architecture to implement this contract layer: in the near-to-medium term, **Android deep customization (Track A)** is the main landing path; in the long term, **a native microkernel (Track B)** is the target research line. The two tracks share the same set of abstract contracts and schemas — this is the anchor point for "future portability" and the foundation for cross-vendor interoperability.

```
┌────────────────────────── Shared Contract Layer ──────────────────────────┐
│                                                                          │
│  Track A: Android deep customization          Track B: Native microkernel  │
│  ┌────────────────────────────┐              ┌────────────────────┐        │
│  │  SystemUI dual-mode + switch  │  UI/UX   │  Cognitive shell ·   │        │
│  │  Launcher3 (regular mode)    │           │  semantic file sys  │        │
│  ├────────────────────────────┤              ├────────────────────┤        │
│  │  Agent brain (privileged app)│  Runtime  │ AI Runtime · Skills │        │
│  │  · AI Runtime · Skills      │  /Agent   │ · Multi-agent fwk   │        │
│  ├────────────────────────────┤              ├────────────────────┤        │
│  │  system_server service      │  System    │ ACB · Intent bus ·   │        │
│  │  cluster (same contracts)   │  Services  │ Memory · Context    │        │
│  ├────────────────────────────┤              ├────────────────────┤        │
│  │  SELinux + platform signing │  Security  │ Rust microkernel ·  │        │
│  │  Kernel: limited custom/HAL │  /Base     │ AI scheduling ·     │        │
│  │                            │           │ KV memory mgmt       │        │
│  └────────────────────────────┘              └────────────────────┘        │
│  Android 16 ecosystem / vendor BSP               NPU · unified mem · TEE   │
└───────────────────────────────────────────────────────────────────────────┘
```

### Core Modules

The following are implemented modules from Track A (Android deep customization), all located in this repository:

#### Security and Trust Base (Policy / Audit / Tokens / Authz)

| Module | Location | Status | Notes |
|---|---|---|---|
| **Policy Engine** | `overlay/frameworks/base/services/.../PolicyEngineService.java` | Core complete | Two-phase evaluation (intent-level pre-check + action-level token issuance), DSL compilation, trie index, token issuance/revocation, hot reload; baseline policy has highest priority and cannot be overridden by user rules |
| **Audit Service** | `overlay/.../AuditService.java` | Core complete | Hash chain + TEE periodic anchoring, role-based redacted queries, chain integrity verification, activity stream subscription |
| **TEE Anchor Manager** | `overlay/.../TeeAnchorManager.java` | Framework complete | Android Keystore EC signatures; hardware StrongBox attestation pending device verification |
| **Confirmation Host Service** | `overlay/.../ConfirmationHostService.java` | Core complete | HMAC verification, anti-replay, confirmation queueing, auto-reject on timeout, and decision callback straight to the execution mediator (not through the Agent runtime) are implemented; ⚠️ **the panel presentation bridge is not yet wired** (the SystemUI panel view is ready; the host→UI call is currently a TODO) |
| **Binder Single-Point Authz** | `overlay/.../AgentCallerAuth.java` | Implemented | Unified onTransact authz for all core services (WBS 1.3) |
| **Capability Token Chain** | `overlay/.../policy/token/` | Implemented | Token signer / validator / revocation list three-piece set |
| **PII Masking Engine** | `overlay/.../PiiMaskingEngine.java` | Implemented | Email / phone / ID card / bank card / credential assignment detection; fail-closed for high-risk scenarios |
| **SELinux Policy** | `overlay/system/sepolicy/private/` | 5 domains | genton_brain / service / systemui / system_server / neverallow |

#### Action Execution System (Deterministic Execution Guarantee)

| Module | Location | Status | Notes |
|---|---|---|---|
| **Execution Mediator** | `frameworks/base/services/.../AgentActionService.kt` | High | 8-state state machine, unified entry, full flow orchestration: policy → confirmation → execution → audit |
| **Input Executor** | `.../action/executor/InputExecutor.kt` | High | Tap / long press / swipe / type / key, with secure window detection |
| **Activity Executor** | `.../action/executor/ActivityExecutor.kt` | High | Launch apps / switch to foreground |
| **Device Control Executor** | `.../action/executor/DeviceControlExecutor.kt` | High | Volume / brightness / system settings panel |
| **Notification Executor** | `.../action/executor/NotificationExecutor.kt` | Mid-high | Clear / reply notifications |
| **Clipboard Executor** | `.../action/executor/ClipboardExecutor.kt` | Mid-high | Set clipboard content, with taint (source trust) validation |
| **Internal Action Executor** | `.../action/executor/InternalActionExecutor.kt` | High | sys./policy./cmd. internal action whitelist routing |
| **Taint Source Validation** | `.../action/TaintRuleInitializer.kt` | Mid-high | Parameter source trust level validation, fail-closed |
| **Action Registry** | `registry/action-registry.v1.json` | Basic | 15 actions registered (ui.* / app.* / notification.* / clipboard.* / device.* / telephony.* / sys.*) |

> ⚠️ **Known simplifications**: in `ActionExecutorDispatcher.java`, app query, intent resolution and notification clear/reply are still MOCK paths; node→coordinate resolution is degraded to a "screen-center fallback" in v1 instead of resolving `UiSnapshot.bounds`. The overlay `InputExecutor` does use the real `InputManager.injectInputEvent`.

#### Perception Layer (Agent System-Level Perception Channels)

| Module | Location | Status |
|---|---|---|
| **Screen Capture Service** | `AgentScreenService.java` + `FrameStreamManager.kt` | Mid-high |
| **Notification Perception Service** | `AgentNotificationService.java` + `NotificationInterceptor.kt` | Mid-high |
| **UI Tree Service** | `AgentUiTreeService.java` + `UiTreeConsistencyValidator.java` | Mid-high |
| **Secure Window Detector** | `SecureWindowDetector.kt` | Implemented |

#### Agent Runtime (GentonBrain Privileged System App, hereafter "the brain")

| Module | Location | Status |
|---|---|---|
| **Session Service** | `session/AgentSessionServiceImpl.kt` | High |
| **Planner** | `planner/Planner.kt` + `GoalModels.kt` | Mid-high |
| **Tool Loop** | `toolloop/ToolLoop.kt` | Mid-high |
| **Multi-Agent Coordinator** | `multiagent/MultiAgentCoordinator.kt` | Mid |
| **DMI Bridge** | `dmi/DmiBridge.kt` + 8 specific Bridges | Mid-high |
| **Resident Model Domain** | `resident/ResidentModelManager.kt` | Framework complete |
| **Prediction Service (S1)** | `prediction/PredictionService.kt` | Framework complete |
| **Memory System Integration** | `memory/` | Mid |
| **Inference Routing Client** | `runtime/AgentRuntimeClient.kt` | Mid |
| **Crash Recovery + Checkpoint** | `lifecycle/` + `checkpoint/` | Mid |

#### SystemUI Interaction Layer (Dual-Mode One-Click Switch)

| Module | Status | Notes |
|---|---|---|
| **Session flow UI** | Mid-high | Message bubbles, action cards, task cards, voice playback |
| **Confirmation panel** | Mid-high | Full-screen secure confirmation |
| **Floating entry** | Mid-high | Edge gesture, floating bubble, sidebar |
| **Dual-mode system** | Mid-high | AI assistant mode / regular mode state machine, Launcher bridge |
| **Activity indicator** | Mid | Agent activity visualization |
| **Privacy dashboard** | Mid-high | Audit timeline, chain integrity display |
| **Rule editor** | Mid | Policy DSL visual editing |
| **Call gate UI** | Mid | Incoming call blocking + AI handling + summary |
| **Memory management UI** | Mid | Entity management + learning rule governance |
| **Posture interaction UI** | Mid | Shake-ready + memo recording + gesture settings |

#### Extended System Services

| Module | Status | Notes |
|---|---|---|
| **Memory System Service** | High (~1047 lines) | Three-tier memory + PII masking + provenance + tiered encryption (AES-GCM) + forgetting cascade; ⚠️ **semantic retrieval is currently text matching** (vector index structure in place, embedding retrieval pending) |
| **Inference Routing Service** | ⚠️ Framework complete (~1164 lines) | Three-tier routing + KV cache structures + memory management + usage metering implemented; ⚠️ **the inference backend is simulated** (`simulateInference`); NPU delegate and a real runtime are pending |
| **Decision Service** | ⚠️ Mid | Choice/Bool/Score decision types + flagship SoC capability gating + degradation chain implemented; ⚠️ **the cloud decision call is not yet bound** (currently a placeholder injection point) |
| **MotionHub Posture Service** | Mid-high | Three tiers (T1/T2/T3) + shake / flip / swipe gestures + sensitive-scenario gating |
| **Call Gate Service** | Mid-high | Incoming call determination + whitelist + L1/L2/L3 handling + emergency number fail-safe |
| **Macro Recorder Service** | Mid | Recording → script generation → replay validation |
| **Intent Learning Service** | Mid | Periodic scanning + candidate generation + suggestion lifecycle + policy engine linkage |
| **Entity Extraction Service** | Mid | Multi-source extraction: contacts / calendar / conversations / notifications / user input |
| **Context Packing Service** | Mid | packContext API + entity change cache invalidation |

---

## Project Structure

```
Genton AI OS/
├── overlay/                          # Genton AI OS overlay layer (AOSP source tree patches)
│   ├── frameworks/base/
│   │   ├── core/java/com/genton/  # AIDL interfaces and shared data models (21 packages, 243 .aidl files)
│   │   ├── services/core/java/      # system_server service implementations (50+ services)
│   │   ├── packages/SystemUI/       # SystemUI deep customization
│   │   └── data/etc/                # Permission declarations + sysconfig
│   └── system/sepolicy/private/     # SELinux policy (5 .te + file/service/seapp_contexts)
├── frameworks/base/                 # Kotlin framework services (earlier version, converging to Java version)
├── packages/
│   ├── apps/GentonBrain/          # Agent brain privileged system app (108 Kotlin files)
│   └── SystemUI/                    # SystemUI standalone module (earlier version)
├── vendor/genton/                 # Platform config + build scripts + policy DSL baseline
│   ├── config/                      # Baseline policy, action registry, value domain catalog, device calibration
│   ├── build/                       # Product mk + AndroidProducts.mk
│   ├── services/                    # Python reference implementation of policy engine
│   └── templates/                   # Third-party Agent development templates (Sample Agent)
├── registry/                        # Action registry (synced with build tree)
├── patches/                         # Patch set (14 patch packages + application scripts)
├── manifests/                       # repo manifest (sync AOSP + Genton AI OS custom code)
├── ml/pii-mask-model/               # PII masking small model resources
├── permissions/                     # Platform permission declarations
├── verification/                    # Static verification gate (14 checks + red team registry)
├── tools/                           # Development tools (build/diag/port/CI)
├── sepolicy/                        # Standalone SELinux policy reference
├── system/                          # System config reference
├── assets/                          # Asset resource reference
├── aidl/                            # AIDL reference
├── ci/                              # CI scripts
├── README.md                        # This document (Chinese)
├── README.en.md                     # This document (English)
└── LICENSE                          # MIT License
```

---

## Deployment and Development

### Prerequisites

- **OS**: Windows (WSL2 Ubuntu 22.04+) or Linux
- **Disk space**: ≥ 200GB (AOSP source + build artifacts)
- **Memory**: ≥ 16GB RAM (32GB+ recommended)
- **Toolchain**: repo, git, JDK 17, Python 3.10+

### Quick Start (Track A: Android Deep Customization)

#### 1. Initialize source tree

```bash
# Install repo tool
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# Initialize repository
mkdir genton-os && cd genton-os
repo init -u https://github.com/forrestsong/Genton -b main -m manifests/genton.xml
repo sync -c -j$(nproc --all)
```

#### 2. Apply Genton AI OS patches

```bash
# Patches are included in overlay/ and patches/
./patches/apply-patches.sh
```

#### 3. Configure environment and build

```bash
source build/envsetup.sh
lunch genton_arm64-eng
m -j$(nproc)
```

#### 4. Flash to device (Pixel series as example)

```bash
adb reboot bootloader
fastboot flashall -w
```

### Development Guide

#### Adding a new action

1. Register the action schema in `registry/action-registry.v1.json` (input/output/risk level/required capability/audit type)
2. Implement the corresponding executor in `frameworks/base/services/.../action/executor/`
3. Register the executor in `AgentActionService.initializeExecutors()`
4. Run validation scripts to ensure compliance:
   ```bash
   python tools/validate_action_registry.py
   python verification/check_1_1_no_prefix_bypass.py
   ```

#### Adding a policy rule

1. Edit `vendor/genton/config/policy_baseline.dsl` or create a new policy pack
2. Compile and validate:
   ```bash
   python tools/policy_dsl_compiler.py --input your_policy.dsl
   ```
3. Run the policy test suite:
   ```bash
   python tools/test_policy_engine_suite.py
   ```

#### Developing a third-party Agent

Refer to the sample agent in `vendor/genton/templates/sample-agent/`:
- Write `SandboxManifest.json` to declare capabilities and intents
- Implement agent logic (Kotlin/Java supported, more languages planned)
- After registration through the system asset library, it can be used with user authorization

### Running Verification Gates

```bash
# Run all static checks (any failure → non-zero exit code)
python verification/run_all.py

# Run a single check
python verification/check_1_3_binder_authz.py

# View current Binder authz status
python verification/check_1_3_binder_authz.py --report
```

---

## Current Completion Overview

| Domain | Completion | Notes |
|---|---|---|
| Security and trust base | 80% | Core mechanisms complete; TEE hardware attestation pending device verification |
| Action execution system | 70% | Framework + 7 executor classes complete; action ecosystem to be expanded |
| Perception system | 65% | UI tree / notification / screen three major perception channels complete |
| Agent runtime | 60% | Session/planner/tool loop/DMI core complete; most model backends in framework state |
| System UI | 70% | Dual mode / session flow / confirmation panel / privacy dashboard complete |
| Extended services | 55% | Memory / inference routing / call gate / posture service etc. complete; sandbox service to be improved |
| Build and toolchain | 75% | Patch system / verification gates / test framework complete |

**Overall assessment**: The architecture is complete, the core security modules are well-implemented, and the minimal closed loop of "perception → intent understanding → planning → secure execution → audit" is in place.

---

## Contributing

Genton AI OS is an open contract layer project. The future of agent-native operating systems cannot be built in isolation by any single company — it requires the joint participation of model vendors, hardware vendors, software service providers, and the security research community to truly establish the foundation for multi-party trust-based collaboration.

### Ways to Participate by Entity

| Participant | Contribution Directions |
|---|---|
| **Model vendors** | Integrate with the inference routing service, providing inference capabilities by contract; model weights and data boundaries are protected by system isolation; call volume is auditable and verifiable |
| **Hardware vendors** | Adapt NPU / TEE / sensor capabilities to the system capability layer; hardware capabilities are invoked by upper layers as capability tokens, traceable and settlable |
| **Software service providers** | Access the asset library as skills / DMIs / macros, providing services within the policy framework; capability scope and data access are strictly limited, with transparent user authorization |
| **Security researchers** | Contribute red team cases, discover and report security vulnerabilities, enhance policy baselines and verification gates |
| **Developer community** | Expand the action ecosystem, enrich the policy rule base, optimize execution performance, write third-party agent templates |

### Contribution Guide

> TODO: Contribution guide in progress. In the meantime, feel free to join the discussion via Issues / PRs.

---

## License

This project is licensed under the **MIT License**, allowing arbitrary modification, distribution, and commercial use. See the [LICENSE](LICENSE) file for details.

---

> **Genton AI OS: Trusted Contract + HUI Interaction + Multi-Party Collaboration.**
>
> Genton AI OS is currently in early development, with the core architecture and trust base already in usable form. All parties are welcome to jointly build an open and trustworthy AI-native operating system base.
