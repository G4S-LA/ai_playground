const form = document.querySelector("#agent-form");
const input = document.querySelector("#request");
const runButton = document.querySelector("#run");
const answer = document.querySelector("#answer");
const statusBadge = document.querySelector("#status");
const currentAction = document.querySelector("#current-action");
const eventsContainer = document.querySelector("#events");
const eventCount = document.querySelector("#event-count");
const connection = document.querySelector("#connection");
const serversContainer = document.querySelector("#mcp-servers");

let activeRunId = null;
let pollGeneration = 0;
let topology = null;

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

function createServerCard(server, index) {
  const fragment = document.createDocumentFragment();
  if (index > 0) {
    const connector = document.createElement("div");
    connector.className = "server-connector";
    connector.textContent = "↓ structured result";
    fragment.append(connector);
  }
  const article = document.createElement("article");
  article.className = "mcp-server";
  article.dataset.server = server.id;

  const header = document.createElement("div");
  header.className = "mcp-server__header";
  const indexBadge = document.createElement("span");
  indexBadge.textContent = String(index + 1).padStart(2, "0");
  const title = document.createElement("div");
  const name = document.createElement("strong");
  name.textContent = server.name;
  const description = document.createElement("small");
  description.textContent = `${server.description} · ${server.transport}`;
  description.title = `${server.source} · ${server.transport}`;
  title.append(name, description);
  const state = document.createElement("span");
  state.className = "mcp-server__state";
  state.textContent = "ready";
  header.append(indexBadge, title, state);

  const tools = document.createElement("div");
  tools.className = "server-tools";
  topology.tools.filter((tool) => tool.serverId === server.id).forEach((tool) => {
    const toolElement = document.createElement("span");
    toolElement.className = "server-tool";
    toolElement.dataset.tool = tool.name;
    toolElement.textContent = tool.name;
    tools.append(toolElement);
  });
  article.append(header, tools);
  fragment.append(article);
  return fragment;
}

function renderTopology(payload) {
  topology = payload;
  serversContainer.replaceChildren(...payload.servers.map(createServerCard));
  connection.textContent = `${payload.servers.length} MCP servers · ${payload.tools.length} tools`;
  connection.classList.add("connection--ready");
}

function resetTopologyState() {
  document.querySelectorAll(".mcp-server").forEach((server) => {
    server.classList.remove("mcp-server--active", "mcp-server--used", "mcp-server--error");
    server.querySelector(".mcp-server__state").textContent = "ready";
  });
  document.querySelectorAll(".server-tool").forEach((tool) => {
    tool.classList.remove("server-tool--active", "server-tool--done");
  });
}

function updateTopologyState(run) {
  resetTopologyState();
  run.events.forEach((event) => {
    if (!event.serverId) return;
    const server = document.querySelector(`.mcp-server[data-server="${event.serverId}"]`);
    const tool = event.toolName
      ? document.querySelector(`.server-tool[data-tool="${event.toolName}"]`)
      : null;
    if (!server) return;
    server.classList.add("mcp-server--used");
    server.querySelector(".mcp-server__state").textContent = "used";
    if (event.type === "model_tool_selected" || event.type === "tool_started") {
      server.classList.add("mcp-server--active");
      server.querySelector(".mcp-server__state").textContent = "active";
      if (tool) tool.classList.add("server-tool--active");
    }
    if (event.type === "tool_completed") {
      server.classList.remove("mcp-server--active");
      server.querySelector(".mcp-server__state").textContent = "used";
      if (tool) {
        tool.classList.remove("server-tool--active");
        tool.classList.add("server-tool--done");
      }
    }
  });
  if (run.status === "failed") {
    const active = document.querySelector(".mcp-server--active");
    if (active) active.classList.add("mcp-server--error");
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
  meta.textContent = event.serverName
    ? `${time} · ${event.serverName} · ${event.toolName}`
    : time;
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
  updateTopologyState(run);
  eventCount.textContent = String(run.events.length);
  if (run.events.length) {
    eventsContainer.replaceChildren(...run.events.map((event, index) =>
      eventElement(event, index === run.events.length - 1)));
    eventsContainer.scrollTop = eventsContainer.scrollHeight;
  } else {
    const empty = document.createElement("p");
    empty.className = "events-empty";
    empty.textContent = "Агент подключает MCP-серверы…";
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

async function loadTopology() {
  try {
    renderTopology(await request("/api/topology"));
  } catch (error) {
    connection.textContent = `MCP registry недоступен: ${error.message}`;
    connection.classList.add("connection--error");
    serversContainer.textContent = error.message;
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const agentRequest = input.value.trim();
  if (!agentRequest || activeRunId) return;
  pollGeneration += 1;
  const generation = pollGeneration;
  runButton.disabled = true;
  input.disabled = true;
  answer.textContent = "Агент выполняет длинный флоу…";
  answer.className = "answer empty";
  resetTopologyState();
  setStatus("running");
  currentAction.textContent = "Создаём запуск агента…";
  try {
    const payload = await request("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ request: agentRequest }),
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

loadTopology();
