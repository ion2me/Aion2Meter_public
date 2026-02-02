const createBattleTimeUI = ({
                              rootEl,
                              tickSelector,
                              statusSelector,
                              totalDamageSelector, // [추가] 총 피해량 선택자 받기
                              graceMs,
                              graceArmMs,
                              visibleClass,
                            } = {}) => {
  if (!rootEl) return null;

  const tickEl = rootEl.querySelector(tickSelector);
  const statusEl = statusSelector ? rootEl.querySelector(statusSelector) : null;
  // [추가] 엘리먼트 찾기
  const totalDmgEl = totalDamageSelector ? rootEl.querySelector(totalDamageSelector) : null;

  let lastBattleTimeMs = null;
  let lastChangedAt = 0;
  let lastSeenAt = 0;

  const formatMMSS = (ms) => {
    const v = Math.max(0, Math.floor(Number(ms) || 0));
    const sec = Math.floor(v / 1000);
    const mm = String(Math.floor(sec / 60)).padStart(2, "0");
    const ss = String(sec % 60).padStart(2, "0");
    return `${mm}:${ss}`;
  };

  const setState = (state) => {
    rootEl.classList.remove("state-fighting", "state-grace", "state-ended");
    if (state) rootEl.classList.add(state);

    if (statusEl) statusEl.dataset.state = state || "";
  };

  const setVisible = (visible) => {
    rootEl.classList.toggle(visibleClass, !!visible);
    if (!visible) {
      setState("");
    }
  };

  const reset = () => {
    lastBattleTimeMs = null;
    lastChangedAt = 0;
    lastSeenAt = 0;

    if (tickEl) tickEl.textContent = "00:00";
    // [추가] 리셋 시 데미지도 0으로
    if (totalDmgEl) totalDmgEl.textContent = "0";
    setState("");
  };

  // [수정] 3번째 인자로 totalDamage 받음
  const update = (now, battleTimeMs, totalDamage = 0) => {
    lastSeenAt = now;

    const bt = Number(battleTimeMs);
    if (!Number.isFinite(bt)) return;

    if (tickEl) tickEl.textContent = formatMMSS(bt);

    // [추가] 총 피해량 업데이트 (콤마 포맷팅)
    if (totalDmgEl) {
      totalDmgEl.textContent = Number(totalDamage).toLocaleString();
    }

    if (lastBattleTimeMs === null) {
      lastBattleTimeMs = bt;
      lastChangedAt = now;
      setState("state-fighting");
      return;
    }

    if (bt !== lastBattleTimeMs) {
      lastBattleTimeMs = bt;
      lastChangedAt = now;
      setState("state-fighting");
      return;
    }

    const frozenMs = Math.max(0, now - lastChangedAt);

    if (frozenMs >= graceMs) setState("state-ended");
    else if (frozenMs >= graceArmMs) setState("state-grace");
    else setState("state-fighting");
  };

  const render = (now) => {
    if (lastBattleTimeMs === null) return;

    const frozenMs = Math.max(0, now - lastChangedAt);
    if (frozenMs >= graceMs) setState("state-ended");
    else if (frozenMs >= graceArmMs) setState("state-grace");
    else setState("state-fighting");
  };

  const getCombatTimeText = () => formatMMSS(lastBattleTimeMs ?? 0);

  return { setVisible, update, render, reset, getCombatTimeText };
};