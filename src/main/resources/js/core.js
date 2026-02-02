class DpsApp {
    static instance;

    constructor() {
        if (DpsApp.instance) return DpsApp.instance;

        this.POLL_MS = 200;
        this.USER_NAME = "-------";

        this.dpsFormatter = new Intl.NumberFormat("ko-KR");
        this.lastJson = null;
        this.isCollapse = false;

        this.lastSnapshot = null;
        this.resetPending = false;

        this.BATTLE_TIME_BASIS = "render";
        this.GRACE_MS = 30000;
        this.GRACE_ARM_MS = 1000;

        this._battleTimeVisible = false;
        this._lastBattleTimeMs = null;

        // [추가] 보스 이름 매핑을 위해 현재 타겟 ID 저장용
        this.currentTargetId = 0;

        DpsApp.instance = this;
    }

    static createInstance() {
        if (!DpsApp.instance) DpsApp.instance = new DpsApp();
        return DpsApp.instance;
    }

    start() {
        this.elList = document.querySelector(".list");
        this.elBossName = document.querySelector(".bossName");
        this.elBossName.textContent = "DPS METER";

        this.resetBtn = document.querySelector(".resetBtn");
        this.collapseBtn = document.querySelector(".collapseBtn");

        // [추가] 저장/매핑 버튼 선택
        this.saveBtn = document.querySelector(".saveBtn");
        this.mapBtn = document.querySelector(".mapBtn");

        this.bindHeaderButtons();
        this.bindDragToMoveWindow();

        this.meterUI = createMeterUI({
            elList: this.elList,
            dpsFormatter: this.dpsFormatter,
            getUserName: () => this.USER_NAME,
            onClickUserRow: (row) => this.detailsUI.open(row),
        });

        this.battleTime = createBattleTimeUI({
            rootEl: document.querySelector(".battleTime"),
            tickSelector: ".tick",
            statusSelector: ".status",
            totalDamageSelector: ".totalDamage",
            graceMs: this.GRACE_MS,
            graceArmMs: this.GRACE_ARM_MS,
            visibleClass: "isVisible",
        });
        this.battleTime.setVisible(false);

        this.detailsPanel = document.querySelector(".detailsPanel");
        this.detailsClose = document.querySelector(".detailsClose");
        this.detailsTitle = document.querySelector(".detailsTitle");
        this.detailsStatsEl = document.querySelector(".detailsStats");
        this.skillsListEl = document.querySelector(".skills");

        this.detailsUI = createDetailsUI({
            detailsPanel: this.detailsPanel,
            detailsClose: this.detailsClose,
            detailsTitle: this.detailsTitle,
            detailsStatsEl: this.detailsStatsEl,
            skillsListEl: this.skillsListEl,
            dpsFormatter: this.dpsFormatter,
            getDetails: (row) => this.getDetails(row),
        });
        window.ReleaseChecker?.start?.();

        setInterval(() => this.fetchDps(), this.POLL_MS);
    }

    nowMs() {
        return typeof performance !== "undefined" ? performance.now() : Date.now();
    }

    safeParseJSON(raw, fallback = {}) {
        if (typeof raw !== "string") {
            return fallback;
        }
        try {
            const value = JSON.parse(raw);
            return value && typeof value === "object" ? value : fallback;
        } catch {
            return fallback;
        }
    }

    async fetchDps() {
        const now = this.nowMs();

        let raw;
        if (window.dpsData && window.dpsData.getDpsData) {
            raw = window.dpsData.getDpsData();
        } else {
            try {
                const res = await fetch("/api/dps");
                if (res.ok) {
                    raw = await res.text();
                }
            } catch (e) {
                // 통신 실패 시 무시
            }
        }

        // 1. 데이터가 아예 없으면 숨김
        if (typeof raw !== "string") {
            this._lastBattleTimeMs = null;
            this._battleTimeVisible = false;
            this.battleTime.setVisible(false);
            return;
        }

        // 2. 데이터가 이전과 똑같으면 화면만 갱신하고 종료 (깜빡임 방지)
        if (raw === this.lastJson) {
            const shouldBeVisible = this._battleTimeVisible && !this.isCollapse;
            this.battleTime.setVisible(shouldBeVisible);

            // 화면이 켜져있다면 기존 값으로 리렌더링 (애니메이션 유지)
            if (shouldBeVisible) {
                this.battleTime.render(now);
            }
            return;
        }

        this.lastJson = raw;

        // 3. 파싱 및 데이터 추출
        // [중요] buildRowsFromPayload에서 totalDamage도 같이 받아와야 함
        const { rows, targetName, battleTimeMs, totalDamage } = this.buildRowsFromPayload(raw);

        // 시간값 갱신 (유효하지 않으면 이전 값 유지)
        if (Number.isFinite(Number(battleTimeMs))) {
            this._lastBattleTimeMs = battleTimeMs;
        }

        // 4. 초기화 요청이 있었다면 숨김
        if (this.resetPending) {
            this._battleTimeVisible = false;
            this.battleTime.setVisible(false);
            if (rows.length === 0) this.resetPending = false;
            return;
        }

        // 5. 렌더링할 데이터 결정 (빈 값이면 마지막 스냅샷 유지)
        let rowsToRender = rows;
        if (rows.length === 0) {
            if (this.lastSnapshot) rowsToRender = this.lastSnapshot;
            else {
                // 스냅샷도 없으면 진짜 숨김
                this._battleTimeVisible = false;
                this.battleTime.setVisible(false);
                return;
            }
        } else {
            this.lastSnapshot = rows;
        }

        // 6. [핵심 수정] 보여주기 조건 완화
        // "전투 시간이 유효한가?" (X) -> "보여줄 데이터가 있는가?" (O)
        // 시간이 멈췄어도(전투 종료) 데이터가 있으면 계속 보여줍니다.
        const hasData = rowsToRender.length > 0 || (totalDamage && totalDamage > 0);
        const shouldBeVisible = !this.isCollapse && hasData;

        this._battleTimeVisible = shouldBeVisible;
        this.battleTime.setVisible(shouldBeVisible);

        if (shouldBeVisible) {
            // 시간은 없으면 0으로 표시하되, 기존 시간이 있으면 그걸 유지
            const timeDisplay = Number.isFinite(Number(battleTimeMs))
                ? battleTimeMs
                : (this._lastBattleTimeMs || 0);

            this.battleTime.update(now, timeDisplay, totalDamage);
            this.battleTime.render(now);
        }

        // 7. 텍스트 및 리스트 렌더링
        this.elBossName.textContent = targetName ? targetName : "DPS METER";
        this.meterUI.updateFromRows(rowsToRender);

        // 상세창 실시간 갱신
        if (this.detailsUI.isOpen()) {
            const openId = this.detailsUI.getOpenedRowId();
            const targetRow = rowsToRender.find((r) => r.id === openId);
            if (targetRow) {
                this.detailsUI.open(targetRow, { force: true });
            }
        }
    }

    buildRowsFromPayload(raw) {
        const payload = this.safeParseJSON(raw, {});
        const targetName = typeof payload?.targetName === "string" ? payload.targetName : "";

        // [추가] 현재 타겟 ID 업데이트 (Backend Step 1에서 추가한 필드)
        if (payload?.targetId) {
            this.currentTargetId = payload.targetId;
        }
        const totalDamage = payload.totalDamage || 0;

        const mapObj = payload?.map && typeof payload.map === "object" ? payload.map : {};
        const rows = this.buildRowsFromMapObject(mapObj);

        const battleTimeMsRaw = payload?.battleTime;
        const battleTimeMs = Number.isFinite(Number(battleTimeMsRaw)) ? Number(battleTimeMsRaw) : null;

        return { rows, targetName, battleTimeMs, totalDamage };
    }

    buildRowsFromMapObject(mapObj) {
        const rows = [];

        for (const [id, value] of Object.entries(mapObj || {})) {
            const isObj = value && typeof value === "object";

            const job = isObj ? (value.job ?? "") : "";
            const nickname = isObj ? (value.nickname ?? "") : "";
            const name = nickname || String(id);

            const dpsRaw = isObj ? value.dps : value;
            const dps = Math.trunc(Number(dpsRaw));

            // 소수점 한자리
            const contribRaw = isObj ? Number(value.damageContribution) : NaN;
            const damageContribution = Number.isFinite(contribRaw)
                ? Math.round(contribRaw * 10) / 10
                : NaN;

            if (!Number.isFinite(dps)) {
                continue;
            }

            rows.push({
                id: String(id),
                name,
                job,
                dps,
                damageContribution,
                isUser: name === this.USER_NAME,
            });
        }

        return rows;
    }

    // [수정] 상세 정보 요청도 API 지원하도록 변경
    async getDetails(row) {
        let raw;

        // 1. Java Bridge
        if (window.dpsData && window.dpsData.getBattleDetail) {
            raw = window.dpsData.getBattleDetail(row.id);
        }
        // 2. Web API
        else {
            try {
                // 비동기 처리 위해 await 사용, row.id 안전하게 처리
                const res = await fetch(`/api/detail?uid=${encodeURIComponent(row.id)}`);
                if (res.ok) {
                    raw = await res.text();
                }
            } catch(e) {
                console.error("Detail fetch failed", e);
            }
        }

        let detailObj = raw;
        // globalThis.uiDebug?.log?.("getBattleDetail", detailObj);

        if (typeof raw === "string") detailObj = this.safeParseJSON(raw, {});
        if (!detailObj || typeof detailObj !== "object") detailObj = {};

        const skills = [];
        let totalDmg = 0;

        let totalTimes = 0;
        let totalCrit = 0;
        let totalParry = 0;
        let totalBack = 0;
        let totalPerfect = 0;
        let totalDouble = 0;

        for (const [code, value] of Object.entries(detailObj)) {
            if (!value || typeof value !== "object") continue;

            const nameRaw = typeof value.skillName === "string" ? value.skillName.trim() : "";
            const baseName = nameRaw ? nameRaw : `스킬 ${code}`;

            // 공통 totals + skills
            const pushSkill = ({
                                   codeKey,
                                   name,
                                   time,
                                   dmg,
                                   crit = 0,
                                   parry = 0,
                                   back = 0,
                                   perfect = 0,
                                   double = 0,
                                   countForTotals = true,
                               }) => {
                const dmgInt = Math.trunc(Number(String(dmg ?? "").replace(/,/g, ""))) || 0;
                if (dmgInt <= 0) {
                    return;
                }

                const t = Number(time) || 0;

                totalDmg += dmgInt;
                if (countForTotals) {
                    totalTimes += t;
                    totalCrit += Number(crit) || 0;
                    totalParry += Number(parry) || 0;
                    totalBack += Number(back) || 0;
                    totalPerfect += Number(perfect) || 0;
                    totalDouble += Number(double) || 0;
                }
                skills.push({
                    code: String(codeKey),
                    name,
                    time: t,
                    crit: Number(crit) || 0,
                    parry: Number(parry) || 0,
                    back: Number(back) || 0,
                    perfect: Number(perfect) || 0,
                    double: Number(double) || 0,
                    dmg: dmgInt,
                });
            };

            // 일반 피해
            pushSkill({
                codeKey: code,
                name: baseName,
                time: value.times,
                dmg: value.damageAmount,
                crit: value.critTimes,
                parry: value.parryTimes,
                back: value.backTimes,
                perfect: value.perfectTimes,
                double: value.doubleTimes,
            });

            // 도트피해
            if (Number(String(value.dotDamageAmount ?? "").replace(/,/g, "")) > 0) {
                pushSkill({
                    codeKey: `${code}-dot`, // 유니크키
                    name: `${baseName} - 지속피해`,
                    time: value.dotTimes,
                    dmg: value.dotDamageAmount,
                    countForTotals: false,
                });
            }
        }

        const pct = (num, den) => {
            if (den <= 0) return 0;
            return Math.round((num / den) * 1000) / 10;
        };
        const contributionPct = Number(row?.damageContribution);
        const combatTime = this.battleTime?.getCombatTimeText?.() ?? "00:00";

        return {
            totalDmg,
            contributionPct,
            totalCritPct: pct(totalCrit, totalTimes),
            totalParryPct: pct(totalParry, totalTimes),
            totalBackPct: pct(totalBack, totalTimes),
            totalPerfectPct: pct(totalPerfect, totalTimes),
            totalDoublePct: pct(totalDouble, totalTimes),
            combatTime,

            skills,
        };
    }

    bindHeaderButtons() {

        // [추가] 2. 보스 이름 변경 버튼 로직
        this.mapBtn?.addEventListener("click", async () => {
            if (!this.currentTargetId || this.currentTargetId === 0) {
                alert("현재 타겟 정보가 없습니다.\n몬스터를 타격한 후 시도해주세요.");
                return;
            }

            const currentName = this.elBossName.textContent;
            const newName = prompt(`현재 타겟(ID: ${this.currentTargetId})의 이름을 변경합니다.\n새 이름을 입력하세요:`, currentName);

            if (newName && newName.trim() !== "") {
                try {
                    // [수정] 서버의 BossMapRequest는 entityId를 요구하므로 키 이름을 맞춥니다.
                    const payload = {
                        entityId: this.currentTargetId,
                        name: newName.trim()
                    };

                    const res = await fetch("/api/boss/map", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" }, // 헤더 명시 권장
                        body: JSON.stringify(payload)
                    });

                    const json = await res.json();

                    if (json.status === "mapped") {
                        alert(`적용 완료!\n[${json.name}]으로 표시됩니다.`);
                        this.elBossName.textContent = json.name;
                    } else {
                        alert("이름 변경 실패: " + (json.message || "알 수 없는 오류"));
                    }
                } catch (e) {
                    console.error(e);
                    alert("이름 변경 중 오류가 발생했습니다.");
                }
            }
        });

        this.collapseBtn?.addEventListener("click", () => {
            this.isCollapse = !this.isCollapse;
            this._battleTimeVisible = !this.isCollapse;
            this.battleTime?.setVisible?.(!this.isCollapse);

            this.elList.style.display = this.isCollapse ? "none" : "grid";

            const iconName = this.isCollapse ? "arrow-down-wide-narrow" : "arrow-up-wide-narrow";
            const iconEl =
                this.collapseBtn.querySelector("svg") || this.collapseBtn.querySelector("[data-lucide]");
            if (!iconEl) {
                return;
            }

            iconEl.setAttribute("data-lucide", iconName);
            lucide.createIcons({ root: this.collapseBtn });
        });

        this.resetBtn?.addEventListener("click", () => {
            this.resetPending = true;
            this.lastSnapshot = null;
            this.lastJson = null;

            this._battleTimeVisible = false;
            this.battleTime.reset();
            this.battleTime.setVisible(false);

            this.detailsUI?.close?.();
            this.meterUI?.onResetMeterUi?.();

            this.elBossName.textContent = "DPS METER";

            // [수정] 리셋 버튼도 API 호출 지원
            if (window.javaBridge?.resetDps) {
                window.javaBridge.resetDps();
            } else {
                fetch("/api/reset").catch(e => console.error(e));
            }
        });
    }

    bindDragToMoveWindow() {
        let isDragging = false;
        let startX = 0,
            startY = 0;
        let initialStageX = 0,
            initialStageY = 0;

        document.addEventListener("mousedown", (e) => {
            isDragging = true;
            startX = e.screenX;
            startY = e.screenY;
            initialStageX = window.screenX;
            initialStageY = window.screenY;
        });

        document.addEventListener("mousemove", (e) => {
            if (!isDragging) return;
            if (!window.javaBridge) return;

            const deltaX = e.screenX - startX;
            const deltaY = e.screenY - startY;
            window.javaBridge.moveWindow(initialStageX + deltaX, initialStageY + deltaY);
        });

        document.addEventListener("mouseup", () => {
            isDragging = false;
        });
    }
}

// 디버그콘솔
const setupDebugConsole = () => {
    if (globalThis.uiDebug?.log) return globalThis.uiDebug;

    const consoleDiv = document.querySelector(".console");
    if (!consoleDiv) {
        globalThis.uiDebug = { log: () => {}, clear: () => {} };
        return globalThis.uiDebug;
    }

    const safeStringify = (value) => {
        if (typeof value === "string") return value;
        if (value instanceof Error) return `${value.name}: ${value.message}`;
        try {
            return JSON.stringify(value);
        } catch {
            return String(value);
        }
    };

    const appendLine = (line) => {
        consoleDiv.style.display = "block";
        consoleDiv.innerHTML += line + "<br>";
        consoleDiv.scrollTop = consoleDiv.scrollHeight;
    };

    globalThis.uiDebug = {
        log(tag, payload) {
            if (globalThis.dpsData?.isDebuggingMode?.() !== true) return;
            const time = new Date().toLocaleTimeString("ko-KR", { hour12: false });
            appendLine(`${time} ${tag} ${safeStringify(payload)}`);
        },
        clear() {
            consoleDiv.innerHTML = "";
        },
    };

    return globalThis.uiDebug;
};

setupDebugConsole();
const dpsApp = DpsApp.createInstance();