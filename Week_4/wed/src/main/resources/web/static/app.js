const $ = (selector) => document.querySelector(selector);
const messages = $("#messages");
const input = $("#message-input");
let currentSessionId = null;
let knownSessions = [];
let busy = false;
let eventVersion = 0;
let eventsBusy = false;

const jsonOptions = (method, body) => ({
  method,
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify(body),
});

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  if (response.status === 204) return null;
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Не удалось выполнить запрос.");
  return body;
}

function setBusy(value) {
  busy = value;
  for (const element of [
    input, $("#send-button"), $("#new-session"), $("#delete-session"),
    $("#refresh-dashboard"), $("#run-due"), $("#clear-dashboard"),
  ]) element.disabled = value;
  for (const button of document.querySelectorAll("#session-list button")) button.disabled = value;
  $("#send-button").textContent = value ? "Подождите…" : "Отправить";
}

async function runAction(action) {
  if (busy) return;
  setBusy(true);
  try {
    await action();
  } catch (error) {
    addMessage("error", error.message);
  } finally {
    setBusy(false);
  }
}

function addMessage(role, text, badge = null) {
  const wrapper = document.createElement("div");
  wrapper.className = `message message--${role}`;
  if (badge) {
    const label = document.createElement("span");
    label.className = "message__badge";
    label.textContent = badge;
    wrapper.appendChild(label);
  }
  const content = document.createElement("span");
  content.textContent = text;
  wrapper.appendChild(content);
  messages.appendChild(wrapper);
  messages.scrollTop = messages.scrollHeight;
  return wrapper;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (!history.length) {
    addMessage("system", "Попросите агента подготовить одноразовый или регулярный новостной отчёт.");
    return;
  }
  for (const item of history) {
    const role = item.role === "assistant" ? "agent" : item.role;
    const badge = item.runId ? "Отчёт" : item.scheduleId ? "Расписание сохранено" : null;
    addMessage(role, item.content, badge);
  }
}

async function refreshSessions() {
  knownSessions = (await requestJson("/api/sessions")).sessions;
  const list = $("#session-list");
  list.replaceChildren();
  for (const session of knownSessions) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-list__item" + (session.id === currentSessionId ? " is-active" : "");
    const title = document.createElement("span");
    title.className = "session-list__title";
    title.textContent = session.title;
    const count = document.createElement("span");
    count.className = "session-list__count";
    count.textContent = session.messageCount;
    button.append(title, count);
    button.addEventListener("click", () => runAction(() => selectSession(session.id)));
    list.appendChild(button);
  }
}

async function selectSession(id) {
  const {session} = await requestJson(`/api/sessions/${encodeURIComponent(id)}`);
  currentSessionId = session.id;
  $("#session-title").textContent = session.title;
  renderMessages(session.messages);
  input.value = "";
  await refreshSessions();
}

function formatDate(value) {
  if (!value) return "—";
  return new Date(value).toLocaleString("ru-RU", {dateStyle: "short", timeStyle: "medium"});
}

function scheduleCard(schedule) {
  const card = document.createElement("article");
  card.className = "info-card";
  const header = document.createElement("div");
  header.className = "info-card__header";
  const title = document.createElement("strong");
  title.textContent = schedule.title;
  const badge = document.createElement("span");
  badge.className = `status status--${schedule.enabled ? "active" : "disabled"}`;
  badge.textContent = schedule.enabled ? "активно" : "завершено";
  header.append(title, badge);
  const timing = document.createElement("p");
  timing.textContent = schedule.cron
    ? `Cron: ${schedule.cron} · ${schedule.timeZone}`
    : "Одноразовый запуск";
  const next = document.createElement("p");
  next.className = "info-card__meta";
  next.textContent = `Следующий запуск: ${schedule.enabled ? formatDate(schedule.nextRunAt) : "—"}`;
  card.append(header, timing, next);
  return card;
}

function reportCard(run) {
  const card = document.createElement("article");
  card.className = "info-card";
  const header = document.createElement("div");
  header.className = "info-card__header";
  const title = document.createElement("strong");
  title.textContent = run.title;
  const badge = document.createElement("span");
  badge.className = `status status--${run.status}`;
  badge.textContent = {completed: "готово", failed: "ошибка", running: "выполняется"}[run.status] || run.status;
  header.append(title, badge);
  const time = document.createElement("p");
  time.className = "info-card__meta";
  time.textContent = formatDate(run.completedAt || run.startedAt);
  card.append(header, time);
  if (run.report) {
    const report = document.createElement("p");
    report.className = "info-card__report";
    report.textContent = run.report;
    card.appendChild(report);
  }
  if (run.error) {
    const error = document.createElement("p");
    error.className = "info-card__error";
    error.textContent = run.error;
    card.appendChild(error);
  }
  return card;
}

async function refreshDashboard() {
  const dashboard = await requestJson("/api/dashboard");
  $("#model-name").textContent = `Модель: ${dashboard.model}`;
  const schedules = dashboard.schedules || [];
  $("#schedule-count").textContent = schedules.length;
  const scheduleList = $("#schedule-list");
  scheduleList.replaceChildren();
  if (!schedules.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "Расписаний пока нет";
    scheduleList.appendChild(empty);
  } else {
    schedules.forEach((item) => scheduleList.appendChild(scheduleCard(item)));
  }

  const history = dashboard.history;
  $("#run-count").textContent = history.totalRuns;
  $("#history-summary").textContent =
    `Успешно: ${history.successfulRuns} · Ошибок: ${history.failedRuns} · Выполняется: ${history.runningRuns}`;
  const reportList = $("#report-list");
  reportList.replaceChildren();
  if (!history.runs.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "Отчётов пока нет";
    reportList.appendChild(empty);
  } else {
    history.runs.forEach((item) => reportList.appendChild(reportCard(item)));
  }
}

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    $("#chat-form").requestSubmit();
  }
});

$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (!message || busy || !currentSessionId) return;
  runAction(async () => {
    if (messages.querySelector(".message--system")) messages.replaceChildren();
    addMessage("user", message);
    const pending = addMessage("agent", "Согласовываю расписание с MCP…");
    pending.classList.add("message--pending");
    input.value = "";
    try {
      const result = await requestJson(
        `/api/sessions/${encodeURIComponent(currentSessionId)}/messages`,
        jsonOptions("POST", {message}),
      );
      pending.remove();
      renderMessages(result.session.messages);
      $("#runner-status").textContent = `Задание сохранено. Следующий запуск: ${formatDate(result.schedule.nextRunAt)}`;
      await Promise.all([refreshSessions(), refreshDashboard()]);
    } catch (error) {
      pending.remove();
      input.value = message;
      throw error;
    }
  });
});

$("#new-session").addEventListener("click", () => runAction(async () => {
  const {session} = await requestJson("/api/sessions", jsonOptions("POST", {}));
  await selectSession(session.id);
}));

$("#delete-session").addEventListener("click", () => {
  if (!currentSessionId || !window.confirm("Удалить этот диалог? Расписания и отчёты MCP сохранятся.")) return;
  runAction(async () => {
    await requestJson(`/api/sessions/${encodeURIComponent(currentSessionId)}`, {method: "DELETE"});
    currentSessionId = null;
    await refreshSessions();
    const next = knownSessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
    await selectSession(next.id);
  });
});

$("#refresh-dashboard").addEventListener("click", () => runAction(refreshDashboard));

$("#run-due").addEventListener("click", () => runAction(async () => {
  const result = await requestJson("/api/run-due", jsonOptions("POST", {}));
  $("#runner-status").textContent = result.reports.length
    ? `Готово отчётов: ${result.reports.length}`
    : "Наступивших заданий сейчас нет.";
  if (currentSessionId) await selectSession(currentSessionId);
  await refreshDashboard();
}));

$("#clear-dashboard").addEventListener("click", () => {
  if (!window.confirm("Удалить все сохранённые задания и всю историю отчётов? Диалоги останутся.")) return;
  runAction(async () => {
    const response = await requestJson("/api/dashboard", {method: "DELETE"});
    $("#runner-status").textContent =
      `Удалено заданий: ${response.result.deletedSchedules}, запусков: ${response.result.deletedRuns}.`;
    await refreshDashboard();
  });
});

async function pollEvents() {
  if (eventsBusy) return;
  eventsBusy = true;
  try {
    const events = await requestJson("/api/events");
    if (events.version !== eventVersion) {
      eventVersion = events.version;
      if (events.lastError) {
        $("#runner-status").textContent = `Ошибка: ${events.lastError}`;
      } else if (events.reports.length) {
        $("#runner-status").textContent = `Автоматически готово отчётов: ${events.reports.length}`;
      }
      if (currentSessionId) await selectSession(currentSessionId);
      await refreshDashboard();
    }
  } catch (_) {
    // Основные действия покажут сетевую ошибку; фоновый опрос её не дублирует.
  } finally {
    eventsBusy = false;
  }
}

runAction(async () => {
  await refreshSessions();
  const initial = knownSessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
  await selectSession(initial.id);
  await refreshDashboard();
  const events = await requestJson("/api/events");
  eventVersion = events.version;
  window.setInterval(pollEvents, 2000);
});
