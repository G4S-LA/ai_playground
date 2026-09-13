const $ = (selector) => document.querySelector(selector);
const input = $("#message-input");
const messages = $("#messages");
const strategyInputs = [...document.querySelectorAll('input[name="strategy"]')];
const settingInputs = [...strategyInputs, $("#keep-recent"), $("#context-window"), $("#facts-max-tokens")];
const number = new Intl.NumberFormat("ru-RU");
let currentChatId = null;
let knownChats = [];
let currentState = null;
let busy = false;
let previewVersion = 0;
let previewTimer;
let settingsQueue = Promise.resolve();
let settingsVersion = 0;

const chatUrl = (id = currentChatId) => `/api/chats/${encodeURIComponent(id)}`;
const money = (value) => value == null ? "—" : value.toFixed(6);
const jsonOptions = (method, body) => ({method, headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(result.error || "Не удалось выполнить запрос");
    Object.assign(error, {statistics: result.statistics, preview: result.preview});
    throw error;
  }
  return result;
}

function addMessage(role, text) {
  const element = document.createElement("div");
  element.className = `message message--${role}`;
  element.textContent = text;
  messages.appendChild(element);
  messages.scrollTop = messages.scrollHeight;
  return element;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (!history.length) addMessage("system", "Выберите настройки и напишите первое сообщение.");
  for (const item of history) {
    addMessage(item.role === "assistant" ? "agent" : "user", item.content || "Модель не успела сформировать текст ответа.");
  }
}

function updateControls() {
  const unavailable = busy || currentState === null;
  for (const element of settingInputs) element.disabled = unavailable || currentState.settings_locked;
  $("#facts-max-tokens").disabled ||= !$("#strategy-facts").checked;
  input.disabled = unavailable;
  $("#send-button").disabled = unavailable;
  $("#send-button").textContent = busy ? "Подождите…" : "Отправить";
  $("#new-chat-button").disabled = busy;
  $("#delete-button").disabled = unavailable;
  $("#copy-button").disabled = unavailable || !currentState.message_count;
  for (const button of document.querySelectorAll("#chat-list button")) button.disabled = busy;
}

function setBusy(value) {
  busy = value;
  ++previewVersion;
  clearTimeout(previewTimer);
  updateControls();
}

function renderState(state) {
  currentState = state;
  for (const radio of strategyInputs) radio.checked = radio.value === state.strategy;
  $("#keep-recent").value = state.keep_recent_messages;
  $("#context-window").value = state.context_window_tokens;
  $("#facts-max-tokens").value = state.facts_max_tokens;
  $("#history-facts").textContent = JSON.stringify(state.facts, null, 2);
  $("#settings-status").textContent = state.settings_locked
    ? "Настройки заблокированы после первого сообщения. Для других настроек создайте новый чат."
    : "Настройки доступны до первого сообщения. N включает текущий вопрос.";
  updateControls();
}

function renderStatistics(stats) {
  renderState(stats.context);
  const total = stats.overall_totals;
  const memory = stats.facts_totals;
  const last = stats.turns.at(-1);
  $("#last-request-tokens").textContent = "Последний запрос в токенах: " + (last
    ? `${last.input_source === "api" ? "" : "≈"}${number.format(last.input_tokens)}` : "—");
  $("#last-answer-tokens").textContent = "Последний ответ в токенах: " + (last
    ? `${last.output_source === "api" ? "" : "≈"}${number.format(last.output_tokens)}` : "—");
  $("#usage-totals").textContent = `Расход этой ветки: ${total.has_estimates ? "≈" : ""}${number.format(total.total_tokens)} токенов · ` +
    (total.unpriced_turns ? `известная стоимость $${money(total.known_cost_usd)}, без цены: ${total.unpriced_turns} вызовов`
      : `стоимость $${money(total.known_cost_usd)}`);
  $("#facts-usage").textContent = `Обновление facts: ${memory.turn_count} запросов, ${memory.has_estimates ? "≈" : ""}${memory.total_tokens} токенов. Включено в расход ветки.`;
  $("#token-details").textContent = `Архив: ≈${stats.history_tokens_estimate} токенов. ` +
    `Расход до ответвления: ${stats.inherited_totals.has_estimates ? "≈" : ""}${stats.inherited_totals.total_tokens} токенов, повторно не списывается. ` +
    "Таблица — ответы пользователю; ↳ обозначает скопированный ход.";
  const rows = $("#usage-rows");
  rows.replaceChildren();
  let tokens = 0, cost = 0;
  let incomplete = false, estimated = false;
  stats.turns.forEach((turn, index) => {
    if (!turn.inherited) {
      tokens += turn.total_tokens;
      cost += turn.cost_usd ?? 0;
      incomplete ||= turn.cost_usd == null;
      estimated ||= turn.input_source !== "api" || turn.output_source !== "api";
    }
    const row = document.createElement("tr");
    const values = [
      `${index + 1}${turn.inherited ? " ↳" : ""}${turn.warning ? " ⚠" : ""}`,
      turn.request_tokens_estimate, turn.history_after_tokens_estimate,
      `${turn.input_source === "api" ? "" : "≈"}${turn.input_tokens}`,
      `${turn.output_source === "api" ? "" : "≈"}${turn.output_tokens}`,
      turn.omitted_history_messages, `${estimated ? "≈" : ""}${tokens}`,
      turn.inherited ? "↳" : money(turn.cost_usd), `${incomplete ? "≥" : ""}${money(cost)}`,
    ];
    for (const value of values) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.appendChild(cell);
    }
    rows.appendChild(row);
  });
}

function readSettings() {
  const values = {
    strategy: $('input[name="strategy"]:checked').value,
    keep_recent_messages: Number($("#keep-recent").value),
    context_window_tokens: Number($("#context-window").value),
    facts_max_tokens: Number($("#facts-max-tokens").value),
  };
  if (![values.keep_recent_messages, values.context_window_tokens, values.facts_max_tokens]
    .every((value) => Number.isSafeInteger(value) && value > 0)) {
    throw new Error("N и лимиты должны быть положительными целыми числами.");
  }
  return values;
}

function saveSettings() {
  if (!currentState || currentState.settings_locked) return settingsQueue;
  const id = currentChatId;
  const values = readSettings();
  const version = ++settingsVersion;
  settingsQueue = settingsQueue.catch(() => {}).then(async () => {
    const result = await requestJson(`${chatUrl(id)}/settings`, jsonOptions("PATCH", values));
    if (id === currentChatId && version === settingsVersion) {
      // Не перезаписываем поля: пользователь мог уже начать вводить следующее значение.
      currentState = result.statistics.context;
      $("#settings-status").textContent = "Настройки сохранены. Их можно менять до первого сообщения.";
    }
  });
  return settingsQueue;
}

function renderPreview(preview) {
  const pending = preview.facts_update_pending;
  $("#token-preview").textContent = `Контекст: ≈${number.format(preview.input_tokens_estimate)} / ${number.format(preview.context_window_tokens)} токенов` +
    (pending ? " · до обновления facts" : "");
  const detail = $("#context-breakdown");
  detail.hidden = false;
  detail.textContent = `Вне контекста: ${preview.omitted_history_messages} старых сообщений. ` +
    (pending ? "Перед ответом обновим facts отдельным запросом и пересчитаем размер." :
      preview.fits ? "Старые сообщения доступны только в архиве." : "Контекст не помещается. Сократите вопрос или создайте чат с другими настройками.");
  const progress = $("#context-progress");
  progress.max = preview.context_window_tokens;
  progress.value = Math.min(preview.input_tokens_estimate, preview.context_window_tokens);
  for (const item of [progress, detail, $("#token-preview")]) item.classList.toggle("is-overflow", !preview.fits && !pending);
}

function schedulePreview() {
  const version = ++previewVersion;
  const id = currentChatId;
  clearTimeout(previewTimer);
  previewTimer = setTimeout(async () => {
    if (busy || !id) return;
    try {
      await settingsQueue;
      const result = await requestJson(`${chatUrl(id)}/preview`, jsonOptions("POST", {message: input.value}));
      if (version === previewVersion && id === currentChatId) renderPreview(result.preview);
    } catch (error) {
      if (version === previewVersion) $("#token-preview").textContent = error.message;
    }
  }, 250);
}

async function refreshChats() {
  knownChats = (await requestJson("/api/chats")).chats;
  $("#chat-list").replaceChildren();
  for (const chat of knownChats) {
    const button = document.createElement("button");
    button.className = "chat-list__item" + (chat.id === currentChatId ? " is-active" : "");
    button.textContent = `${chat.checkpoint_id ? "↳ " : ""}${chat.title} · ${chat.message_count}`;
    button.title = chat.title;
    button.disabled = busy;
    button.addEventListener("click", () => runAction(() => selectChat(chat.id)));
    $("#chat-list").appendChild(button);
  }
}

async function selectChat(id) {
  await settingsQueue.catch(() => {});
  const result = await requestJson(`${chatUrl(id)}/messages`);
  const changedChat = currentChatId !== id;
  currentChatId = id;
  input.value = "";
  $("#chat-title").textContent = result.chat.title;
  $("#branch-status").textContent = result.chat.checkpoint_id
    ? "Копия диалога. Продолжение независимо от исходного чата." : "";
  renderMessages(result.messages);
  renderStatistics(result.statistics);
  if (changedChat) {
    $("#facts-details").open = false;
    $(".metrics").scrollTop = 0;
  }
  if (result.statistics.turns.at(-1)?.warning) addMessage("error", result.statistics.turns.at(-1).warning);
  await refreshChats();
}

async function runAction(action) {
  if (busy) return;
  setBusy(true);
  try { await action(); }
  catch (error) { addMessage("error", error.message); }
  finally { setBusy(false); schedulePreview(); }
}

$("#new-chat-button").addEventListener("click", () => runAction(async () => {
  const result = await requestJson("/api/chats", jsonOptions("POST", {}));
  await selectChat(result.chat.id);
}));
$("#copy-button").addEventListener("click", () => runAction(async () => {
  const result = await requestJson(`${chatUrl()}/copy`, jsonOptions("POST", {}));
  await selectChat(result.chat.id);
}));
$("#delete-button").addEventListener("click", () => {
  if (!window.confirm("Удалить текущий чат? Другие диалоги и их копии сохранятся.")) return;
  runAction(async () => {
    await settingsQueue.catch(() => {});
    await requestJson(chatUrl(), {method: "DELETE"});
    await refreshChats();
    const chat = knownChats[0] || (await requestJson("/api/chats", jsonOptions("POST", {}))).chat;
    await selectChat(chat.id);
  });
});

for (const element of settingInputs) {
  element.addEventListener("change", async () => {
    updateControls();
    try { await saveSettings(); schedulePreview(); }
    catch (error) { $("#settings-status").textContent = error.message; }
  });
}
input.addEventListener("input", schedulePreview);
input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); $("#chat-form").requestSubmit(); }
});
$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const draft = input.value;
  const message = draft.trim();
  if (!message || busy || !currentState) return;
  runAction(async () => {
    await saveSettings();
    if (!currentState.message_count) messages.replaceChildren();
    addMessage("user", message);
    const pending = addMessage("agent", currentState.strategy === "facts" ? "Обновляем facts и готовим ответ…" : "Модель готовит ответ…");
    pending.classList.add("message--pending");
    input.value = "";
    try {
      await requestJson(`${chatUrl()}/messages`, jsonOptions("POST", {message}));
      await selectChat(currentChatId);
    } catch (error) {
      // Перечитываем серверное состояние также при разрыве соединения: первый
      // запрос мог уже заблокировать настройки или сохранить ответ.
      try { await selectChat(currentChatId); }
      catch { if (error.statistics) renderStatistics(error.statistics); pending.remove(); }
      input.value = draft;
      addMessage("error", error.message);
      if (error.preview) renderPreview(error.preview);
    }
  });
});

runAction(async () => {
  await refreshChats();
  const chat = knownChats[0] || (await requestJson("/api/chats", jsonOptions("POST", {}))).chat;
  await selectChat(chat.id);
});
