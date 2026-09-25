const form = document.querySelector("#pipeline-form");
const input = document.querySelector("#request");
const runButton = document.querySelector("#run");
const answer = document.querySelector("#answer");
const statusBadge = document.querySelector("#status");
const currentAction = document.querySelector("#current-action");
const eventsContainer = document.querySelector("#events");
const eventCount = document.querySelector("#event-count");
const connection = document.querySelector("#connection");

let activeRunId = null;
let pollGeneration = 0;

async function request(url, options) {
  const response = await fetch(url, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function setStatus(status) {
  const labels = { running: "в работе", completed: "готово", failed: "ошибка", idle: "ожидание" };
  statusBadge.textContent = labels[status] || status;
  statusBadge.className = `status status--${status}`;
}

function resetStages() {
  document.querySelectorAll(".stage").forEach((stage) => {
    stage.classList.remove("stage--active", "stage--done", "stage--error");
  });
}

function updateStages(run) {
  resetStages();
  run.events.forEach((event) => {
    if (!event.toolName) return;
    const stage = document.querySelector(`#stage-${event.toolName}`);
    if (!stage) return;
    if (event.type === "model_tool_selected" || event.type === "tool_started") {
      stage.classList.add("stage--active");
    }
    if (event.type === "tool_completed") {
      stage.classList.remove("stage--active");
      stage.classList.add("stage--done");
    }
  });
  if (run.status === "failed") {
    const active = document.querySelector(".stage--active");
    if (active) active.classList.add("stage--error");
  }
}

function formatPayload(payload) {
  if (!payload) return "";
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch (_) {
    return payload;
  }
}

function eventElement(event, isLatest) {
  const article = document.createElement("article");
  article.className = `event event--${event.type}${isLatest ? " event--latest" : ""}`;

  const marker = document.createElement("span");
  marker.className = "event-marker";
  const body = document.createElement("div");
  const meta = document.createElement("small");
  const time = new Date(event.createdAt).toLocaleTimeString("ru-RU");
  meta.textContent = event.toolName ? `${time} · ${event.toolName}` : time;
  const title = document.createElement("p");
  title.textContent = event.message;
  body.append(meta, title);

  if (event.payload) {
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = event.type === "tool_completed" ? "Результат" : "Данные";
    const pre = document.createElement("pre");
    pre.textContent = formatPayload(event.payload);
    details.append(summary, pre);
    body.append(details);
  }
  article.append(marker, body);
  return article;
}

function renderRun(run) {
  setStatus(run.status);
  currentAction.textContent = run.currentAction;
  currentAction.classList.toggle("current-action--running", run.status === "running");
  updateStages(run);
  eventCount.textContent = String(run.events.length);
  if (run.events.length) {
    eventsContainer.replaceChildren(...run.events.map((event, index) =>
      eventElement(event, index === run.events.length - 1)));
    eventsContainer.scrollTop = eventsContainer.scrollHeight;
  } else {
    const empty = document.createElement("p");
    empty.className = "events-empty";
    empty.textContent = "Агент запускается…";
    eventsContainer.replaceChildren(empty);
  }

  if (run.status === "completed") {
    answer.textContent = run.answer;
    answer.classList.remove("empty", "error");
  } else if (run.status === "failed") {
    answer.textContent = `Ошибка: ${run.error}`;
    answer.classList.remove("empty");
    answer.classList.add("error");
  }
}

async function pollRun(runId, generation) {
  while (activeRunId === runId && pollGeneration === generation) {
    const payload = await request(`/api/runs/${runId}`);
    renderRun(payload);
    if (payload.status !== "running") {
      activeRunId = null;
      runButton.disabled = false;
      input.disabled = false;
      return;
    }
    await wait(300);
  }
}

async function loadTools() {
  try {
    const payload = await request("/api/tools");
    connection.textContent = `MCP tools: ${payload.tools.map((tool) => tool.name).join(" · ")}`;
    connection.classList.add("connection--ready");
  } catch (error) {
    connection.textContent = `MCP недоступен: ${error.message}`;
    connection.classList.add("connection--error");
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const pipelineRequest = input.value.trim();
  if (!pipelineRequest || activeRunId) return;
  pollGeneration += 1;
  const generation = pollGeneration;
  runButton.disabled = true;
  input.disabled = true;
  answer.textContent = "Агент выполняет задачу…";
  answer.className = "answer empty";
  resetStages();
  setStatus("running");
  currentAction.textContent = "Создаём запуск агента…";
  try {
    const payload = await request("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ request: pipelineRequest }),
    });
    activeRunId = payload.run.id;
    renderRun(payload.run);
    await pollRun(activeRunId, generation);
  } catch (error) {
    activeRunId = null;
    setStatus("failed");
    currentAction.textContent = error.message;
    answer.textContent = `Ошибка: ${error.message}`;
    answer.className = "answer error";
    runButton.disabled = false;
    input.disabled = false;
  }
});

loadTools();
