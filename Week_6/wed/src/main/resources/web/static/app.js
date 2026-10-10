const $ = (selector) => document.querySelector(selector);

async function requestJson(path, options = {}) {
  const response = await fetch(path, {
    headers: {"Content-Type": "application/json", ...(options.headers || {})},
    ...options,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

function toast(message, isError = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast toast--visible${isError ? " toast--error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = window.setTimeout(() => element.className = "toast", 4200);
}

function setBusy(button, value, label) {
  if (!button.dataset.label) button.dataset.label = button.querySelector("span")?.textContent || button.textContent;
  button.disabled = value;
  const target = button.querySelector("span") || button;
  target.textContent = value ? label : button.dataset.label;
}

function structuredStats(stats) {
  return stats.find((item) => item.strategy === "structured") || {chunks: 0, documents: 0};
}

async function loadInfo() {
  try {
    const info = await requestJson("/api/info");
    $("#embedding-model").textContent = info.embeddingModel;
    $("#chat-model").textContent = info.chatModel;
    $("#ollama-url").textContent = info.ollamaUrl.replace(/^https?:\/\//, "");
    const stats = structuredStats(info.stats);
    $("#chunk-count").textContent = stats.chunks;
    $("#document-count").textContent = stats.documents;

    const dot = $("#status-dot");
    if (info.status.reachable && info.chatModelInstalled && info.embeddingModelInstalled) {
      dot.className = "status-dot status-dot--online";
      $("#status-title").textContent = "Обе модели готовы";
    } else if (info.status.reachable) {
      dot.className = "status-dot status-dot--warning";
      const missing = [
        !info.embeddingModelInstalled && info.embeddingModel,
        !info.chatModelInstalled && info.chatModel,
      ].filter(Boolean).join(", ");
      $("#status-title").textContent = `Не установлено: ${missing}`;
    } else {
      dot.className = "status-dot status-dot--offline";
      $("#status-title").textContent = "Ollama недоступна";
    }
  } catch (error) {
    $("#status-dot").className = "status-dot status-dot--offline";
    $("#status-title").textContent = "Ошибка проверки";
    toast(error.message, true);
  }
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function renderResult(data) {
  const root = $("#result");
  root.className = "result panel";
  root.replaceChildren();

  const answer = element("article", "answer-card");
  const answerHeading = element("header", "result-heading");
  const titleGroup = element("div");
  titleGroup.append(element("p", "eyebrow", "Ответ локальной модели"), element("h2", "", "По найденным источникам"));
  const timing = element("span", "timing", `${data.totalMs} ms`);
  answerHeading.append(titleGroup, timing);
  answer.append(answerHeading, element("p", "answer-text", data.answer));

  const metrics = element("div", "metrics");
  metrics.append(
    metric("Retrieval", `${data.retrievalMs} ms`),
    metric("Generation", `${data.generationMs} ms`),
    metric("Источников", String(data.sources.length)),
  );
  answer.append(metrics);

  const sources = element("aside", "sources-card");
  const sourcesHeading = element("div", "result-heading");
  const sourcesTitle = element("div");
  sourcesTitle.append(element("p", "eyebrow", "Local retrieval"), element("h2", "", "Доказательства"));
  sourcesHeading.append(sourcesTitle);
  sources.append(sourcesHeading);

  for (const source of data.sources) {
    const details = element("details", "source");
    const summary = element("summary");
    summary.append(
      element("b", "source__citation", `[${source.citation}]`),
      element("span", "source__name", source.source),
      element("i", "source__score", Number(source.score).toFixed(3)),
    );
    const meta = element("p", "source__meta", `${source.section} · ${source.chunkId}`);
    const quote = element("blockquote", "", source.text);
    details.append(summary, meta, quote);
    sources.append(details);
  }

  root.append(answer, sources);
  root.scrollIntoView({behavior: "smooth", block: "start"});
}

function metric(label, value) {
  const node = element("div");
  node.append(element("span", "", label), element("strong", "", value));
  return node;
}

$("#ask-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = $("#question").value.trim();
  if (!question) return;
  const button = $("#ask-button");
  setBusy(button, true, "Ищем и генерируем…");
  try {
    const result = await requestJson("/api/ask", {
      method: "POST",
      body: JSON.stringify({question, topK: Number($("#top-k").value)}),
    });
    renderResult(result);
  } catch (error) {
    toast(error.message, true);
  } finally {
    setBusy(button, false);
  }
});

$("#question").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    $("#ask-form").requestSubmit();
  }
});

$("#refresh").addEventListener("click", loadInfo);

$("#rebuild").addEventListener("click", async () => {
  const button = $("#rebuild");
  setBusy(button, true, "Строим embeddings…");
  try {
    const result = await requestJson("/api/index", {method: "POST", body: "{}"});
    toast(`Индекс готов: ${result.chunks} чанков из ${result.documents} документов.`);
    await loadInfo();
  } catch (error) {
    toast(error.message, true);
  } finally {
    setBusy(button, false);
  }
});

loadInfo();
