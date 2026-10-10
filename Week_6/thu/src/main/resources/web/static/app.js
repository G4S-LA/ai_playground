const elements = {
    modelCard: document.querySelector("#model-card"),
    runtimeState: document.querySelector("#runtime-state"),
    modelName: document.querySelector("#model-name"),
    modelDetails: document.querySelector("#model-details"),
    quantization: document.querySelector("#quantization"),
    modelSize: document.querySelector("#model-size"),
    prompt: document.querySelector("#prompt"),
    compare: document.querySelector("#compare"),
    comparison: document.querySelector("#comparison"),
    toast: document.querySelector("#toast"),
};

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {"Content-Type": "application/json", ...(options.headers || {})},
        ...options,
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
    return body;
}

function escapeHtml(value = "") {
    return String(value).replace(/[&<>'"]/g, char => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
    })[char]);
}

function formatBytes(value) {
    if (value == null) return "нет данных";
    const units = ["B", "KB", "MB", "GB"];
    let size = Number(value);
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
        size /= 1024;
        unit += 1;
    }
    return `${size.toFixed(unit > 1 ? 2 : 0)} ${units[unit]}`;
}

function showToast(message, error = false) {
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", error);
    elements.toast.classList.add("visible");
    window.setTimeout(() => elements.toast.classList.remove("visible"), 3800);
}

function setBusy(busy) {
    if (!elements.compare.dataset.label) elements.compare.dataset.label = elements.compare.innerHTML;
    elements.compare.disabled = busy;
    elements.compare.innerHTML = busy ? "Два локальных<br>запуска…" : elements.compare.dataset.label;
}

function applyProfile(prefix, profile) {
    document.querySelector(`#${prefix}-temperature`).textContent = profile.temperature;
    document.querySelector(`#${prefix}-tokens`).textContent = profile.maxTokens;
    document.querySelector(`#${prefix}-context`).textContent = Number(profile.contextWindow).toLocaleString("ru-RU");
}

function qualityTags(quality) {
    return `
        <div class="quality-tags">
            <span class="${quality.concise ? "pass" : "miss"}">до 220 слов</span>
            <span class="${quality.structured ? "pass" : "miss"}">структура</span>
            <span class="${quality.briefAnswer ? "pass" : "miss"}">краткий ответ</span>
        </div>`;
}

function metric(label, value, detail = "") {
    return `<div><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong>${detail ? `<small>${escapeHtml(detail)}</small>` : ""}</div>`;
}

function runCard(run, kind) {
    const metrics = run.metrics;
    const resources = metrics.resources || {};
    return `
        <article class="answer ${kind}">
            <p class="eyebrow">${escapeHtml(run.profile.label)}</p>
            <div class="answer-heading">
                <h3>${kind === "optimized" ? "Настроенный ответ" : "Обычный ответ"}</h3>
                <span>${run.quality.words} слов</span>
            </div>
            <div class="answer-text">${escapeHtml(run.answer)}</div>
            ${qualityTags(run.quality)}
            <div class="metrics">
                ${metric("Всего", `${metrics.totalMs} ms`, `load ${metrics.loadMs} ms`)}
                ${metric("Генерация", `${metrics.generationMs} ms`, `${metrics.responseTokens} токенов`)}
                ${metric("Скорость", `${Number(metrics.tokensPerSecond).toFixed(1)} tok/s`)}
                ${metric("Loaded", formatBytes(resources.loadedBytes), `VRAM ${formatBytes(resources.vramBytes)}`)}
            </div>
        </article>`;
}

function renderComparison(result) {
    const order = result.executionOrder.map(value => value === "baseline" ? "до" : "после").join(" → ");
    elements.comparison.className = "comparison";
    elements.comparison.innerHTML = `
        <div class="order-strip">Последовательный запуск · порядок: <b>${order}</b></div>
        <div class="answer-grid">
            ${runCard(result.baseline, "baseline")}
            ${runCard(result.optimized, "optimized")}
        </div>`;
    elements.comparison.scrollIntoView({behavior: "smooth", block: "start"});
}

async function compare() {
    const prompt = elements.prompt.value.trim();
    if (!prompt) return showToast("Сначала введите запрос.", true);
    setBusy(true);
    elements.comparison.className = "comparison empty";
    elements.comparison.innerHTML = '<div class="empty-copy pulse">Последовательно выполняем два запроса к локальной модели…</div>';
    try {
        const result = await api("/api/compare", {
            method: "POST",
            body: JSON.stringify({prompt}),
        });
        renderComparison(result);
    } catch (error) {
        elements.comparison.innerHTML = '<div class="empty-copy">Не удалось выполнить сравнение.</div>';
        showToast(error.message, true);
    } finally {
        setBusy(false);
    }
}

async function initialise() {
    try {
        const info = await api("/api/info");
        const model = info.model;
        elements.modelName.textContent = model.model;
        elements.modelDetails.textContent = [model.parameterSize, model.quantization].filter(Boolean).join(" · ") || "характеристики не получены";
        elements.quantization.textContent = model.quantization || "—";
        elements.modelSize.textContent = formatBytes(model.modelBytes);
        applyProfile("baseline", info.baseline);
        applyProfile("optimized", info.optimized);
        if (model.reachable && model.installed) {
            elements.runtimeState.textContent = "Runtime · модель готова";
        } else if (model.reachable) {
            elements.modelCard.classList.add("warning");
            elements.runtimeState.textContent = "Runtime · модель не установлена";
        } else {
            elements.modelCard.classList.add("offline");
            elements.runtimeState.textContent = "Runtime · Ollama недоступна";
        }
    } catch (error) {
        elements.modelCard.classList.add("offline");
        elements.runtimeState.textContent = "Runtime · ошибка проверки";
        showToast(error.message, true);
    }
}

elements.compare.addEventListener("click", compare);
elements.prompt.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") compare();
});
document.querySelectorAll(".examples button").forEach(button => {
    button.addEventListener("click", () => {
        elements.prompt.value = button.textContent;
        elements.prompt.focus();
    });
});

initialise();
