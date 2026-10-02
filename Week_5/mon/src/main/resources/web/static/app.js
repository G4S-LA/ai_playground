const $ = (selector) => document.querySelector(selector);
const filesInput = $("#files");
const dropZone = $("#drop-zone");
const results = $("#results");

async function request(url, options = {}) {
    const response = await fetch(url, {
        headers: { "Content-Type": "application/json", ...(options.headers || {}) },
        ...options,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    return payload;
}

function toast(message, error = false) {
    const node = $("#toast");
    node.textContent = message;
    node.className = `toast visible${error ? " error" : ""}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.className = "toast", 4200);
}

function busy(button, active, label) {
    if (active) {
        button.dataset.label = button.textContent;
        button.textContent = label;
        button.disabled = true;
    } else {
        button.textContent = button.dataset.label || button.textContent;
        button.disabled = false;
    }
}

function renderStats(stats) {
    $("#stats").innerHTML = stats.map(item => `
        <div class="stat ${item.strategy}">
            <div class="stat-name"><i></i>${escapeHtml(item.strategy)}</div>
            <strong>${item.chunks}</strong><span>чанков</span>
            <dl>
                <div><dt>Документы</dt><dd>${item.documents}</dd></div>
                <div><dt>Средний размер</dt><dd>${Math.round(item.averageCharacters)} зн.</dd></div>
                <div><dt>≈ токенов</dt><dd>${Math.round(item.averageTokens)}</dd></div>
            </dl>
        </div>
    `).join("");
}

async function loadInfo() {
    try {
        const info = await request("/api/info");
        $("#provider").textContent = info.embeddingProvider;
        renderStats(info.stats);
    } catch (error) {
        toast(error.message, true);
    }
}

function updateFileList() {
    const files = [...filesInput.files];
    $("#file-list").classList.toggle("muted", files.length === 0);
    $("#file-list").innerHTML = files.length
        ? files.map(file => `<span>${escapeHtml(file.name)} <small>${formatBytes(file.size)}</small></span>`).join("")
        : "Файлы ещё не выбраны";
}

function fileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(",", 2)[1]);
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(file);
    });
}

$("#upload").addEventListener("click", async () => {
    const button = $("#upload");
    const files = [...filesInput.files];
    if (!files.length) return toast("Сначала выберите документы.", true);
    busy(button, true, "Загрузка…");
    try {
        const encoded = await Promise.all(files.map(async file => ({ name: file.name, base64: await fileToBase64(file) })));
        const response = await request("/api/documents", { method: "POST", body: JSON.stringify({ files: encoded }) });
        toast(`Загружено файлов: ${response.files.length}`);
    } catch (error) {
        toast(error.message, true);
    } finally {
        busy(button, false);
    }
});

$("#build").addEventListener("click", async () => {
    const button = $("#build");
    busy(button, true, "Вычисляем эмбеддинги…");
    try {
        const payload = {
            strategy: "both",
            fixedSize: Number($("#fixed-size").value),
            overlap: Number($("#overlap").value),
            structuredMaxSize: Number($("#structured-size").value),
        };
        const response = await request("/api/index", { method: "POST", body: JSON.stringify(payload) });
        renderStats(response.stats);
        const summary = response.results.map(item => `${item.strategy}: ${item.chunks}`).join(", ");
        toast(`Индексы готовы · ${summary}`);
    } catch (error) {
        toast(error.message, true);
    } finally {
        busy(button, false);
    }
});

$("#compare").addEventListener("click", compare);
$("#query").addEventListener("keydown", event => { if (event.key === "Enter") compare(); });
$("#refresh").addEventListener("click", loadInfo);
document.querySelectorAll("[data-strategy]").forEach(button => {
    button.addEventListener("click", () => search(button.dataset.strategy));
});
filesInput.addEventListener("change", updateFileList);
dropZone.addEventListener("dragover", event => { event.preventDefault(); dropZone.classList.add("dragging"); });
dropZone.addEventListener("dragleave", () => dropZone.classList.remove("dragging"));
dropZone.addEventListener("drop", event => {
    event.preventDefault();
    dropZone.classList.remove("dragging");
    filesInput.files = event.dataTransfer.files;
    updateFileList();
});

async function compare() {
    const query = $("#query").value.trim();
    if (!query) return toast("Введите поисковый запрос.", true);
    const button = $("#compare");
    busy(button, true, "Ищем…");
    results.innerHTML = '<div class="results-loading">Сравниваем два индекса…</div>';
    try {
        const data = await request("/api/compare", {
            method: "POST",
            body: JSON.stringify({ query, topK: Number($("#top-k").value) }),
        });
        results.className = "comparison";
        results.innerHTML = resultColumn("fixed", data.fixed) + resultColumn("structured", data.structured);
    } catch (error) {
        results.className = "empty-state";
        results.innerHTML = `<p>${escapeHtml(error.message)}</p>`;
        toast(error.message, true);
    } finally {
        busy(button, false);
    }
}

async function search(strategy) {
    const query = $("#query").value.trim();
    if (!query) return toast("Введите поисковый запрос.", true);
    results.innerHTML = '<div class="results-loading">Ищем ближайшие чанки…</div>';
    try {
        const data = await request("/api/search", {
            method: "POST",
            body: JSON.stringify({ query, strategy, topK: Number($("#top-k").value) }),
        });
        results.className = "comparison single";
        results.innerHTML = resultColumn(strategy, data.results);
    } catch (error) {
        toast(error.message, true);
    }
}

function resultColumn(strategy, hits) {
    const cards = hits.length ? hits.map((hit, index) => `
        <article class="hit">
            <header><b>${index + 1}</b><span>${escapeHtml(hit.source)}</span><strong>${Number(hit.score).toFixed(3)}</strong></header>
            <h3>${escapeHtml(hit.section)}</h3>
            <p>${escapeHtml(hit.text)}</p>
            <footer><code>${escapeHtml(hit.chunkId)}</code><span>${hit.tokenCount} токенов${hit.page ? ` · стр. ${hit.page}` : ""}</span></footer>
        </article>
    `).join("") : '<div class="no-hits">Индекс пуст. Сначала запустите индексацию.</div>';
    return `<section class="result-column"><h2><i class="dot ${strategy}"></i>${strategy}</h2>${cards}</section>`;
}

function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} Б`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} КБ`;
    return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
}

loadInfo();
