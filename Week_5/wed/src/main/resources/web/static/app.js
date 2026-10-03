const elements = {
    chatModel: document.querySelector("#chat-model"),
    embeddingModel: document.querySelector("#embedding-model"),
    chunkCount: document.querySelector("#chunk-count"),
    documentCount: document.querySelector("#document-count"),
    questionCount: document.querySelector("#question-count"),
    candidateK: document.querySelector("#candidate-k"),
    finalK: document.querySelector("#final-k"),
    threshold: document.querySelector("#threshold"),
    question: document.querySelector("#question"),
    compare: document.querySelector("#compare"),
    comparison: document.querySelector("#comparison"),
    rebuild: document.querySelector("#rebuild"),
    runEvaluation: document.querySelector("#run-evaluation"),
    progress: document.querySelector("#evaluation-progress"),
    progressLabel: document.querySelector("#progress-label"),
    progressValue: document.querySelector("#progress-value"),
    progressBar: document.querySelector("#progress-bar"),
    summary: document.querySelector("#summary"),
    questions: document.querySelector("#questions"),
    toast: document.querySelector("#toast"),
};

let controls = [];

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

function fixed(value) {
    return Number(value || 0).toFixed(3);
}

function percent(value) {
    return `${Math.round((value || 0) * 100)}%`;
}

function settings() {
    const value = {
        candidateK: Number(elements.candidateK.value),
        finalK: Number(elements.finalK.value),
        similarityThreshold: Number(elements.threshold.value),
    };
    if (value.candidateK < 1 || value.candidateK > 30) throw new Error("Top-K до фильтра должен быть от 1 до 30.");
    if (value.finalK < 1 || value.finalK > value.candidateK) throw new Error("Top-K после фильтра должен быть не больше числа кандидатов.");
    if (value.similarityThreshold < -1 || value.similarityThreshold > 1) throw new Error("Threshold должен быть от -1 до 1.");
    return value;
}

function showToast(message, error = false) {
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", error);
    elements.toast.classList.add("visible");
    window.setTimeout(() => elements.toast.classList.remove("visible"), 3500);
}

function setBusy(button, busy, label) {
    if (!button.dataset.label) button.dataset.label = button.innerHTML;
    button.disabled = busy;
    button.innerHTML = busy ? label : button.dataset.label;
}

function candidateCard(candidate, index, enhanced) {
    const state = candidate.selected ? "selected" : (!candidate.passedThreshold ? "rejected" : "");
    const label = candidate.selected ? `S${index + 1}` : (!candidate.passedThreshold ? "×" : "—");
    return `
        <article class="candidate ${state}">
            <header>
                <b>${label}</b>
                <span title="${escapeHtml(candidate.source)}">${escapeHtml(candidate.source)} · ${escapeHtml(candidate.section)}</span>
                <em>${candidate.selected ? "in context" : (candidate.passedThreshold ? "candidate" : "filtered")}</em>
            </header>
            <dl>
                <div><dt>Similarity</dt><dd>${fixed(candidate.similarity)}</dd></div>
                <div><dt>Lexical</dt><dd>${fixed(candidate.lexicalScore)}</dd></div>
                <div><dt>${enhanced ? "Rerank" : "Vector rank"}</dt><dd>${fixed(candidate.rerankScore)}</dd></div>
            </dl>
            <details><summary>Показать chunk · ${escapeHtml(candidate.chunkId)}</summary><p>${escapeHtml(candidate.text)}</p></details>
        </article>`;
}

function resultColumn(title, eyebrow, answer, enhanced) {
    const selected = answer.sources.length;
    const selectedCandidates = answer.candidates.filter(candidate => candidate.selected);
    const rest = answer.candidates.filter(candidate => !candidate.selected);
    const ordered = [...selectedCandidates, ...rest];
    return `
        <article class="result ${enhanced ? "enhanced" : "baseline"}">
            <header class="result-header">
                <p class="eyebrow">${eyebrow}</p>
                <div><h3>${title}</h3><time>${answer.elapsedMs} ms${answer.rewriteMs ? ` · rewrite ${answer.rewriteMs} ms` : ""}</time></div>
                <p class="query-pill"><b>Search query</b>${escapeHtml(answer.searchQuery)}</p>
                <div class="funnel">
                    <div><strong>${answer.candidates.length}</strong><span>до фильтра</span></div>
                    <i>→</i>
                    <div><strong>${selected}</strong><span>в контексте</span></div>
                </div>
            </header>
            <div class="answer-copy">${escapeHtml(answer.answer)}</div>
            <div class="candidate-list">
                <h4>${enhanced ? "Threshold + heuristic rerank" : "Cosine similarity only"}</h4>
                ${ordered.map((candidate, index) => candidateCard(candidate, index, enhanced)).join("")}
            </div>
        </article>`;
}

function renderComparison(result) {
    elements.comparison.classList.remove("empty");
    elements.comparison.innerHTML =
        resultColumn("Обычный retrieval", "без rewrite / filter", result.baseline, false) +
        resultColumn("Улучшенный retrieval", "rewrite + threshold + rerank", result.enhanced, true);
}

function renderQuestions(results = []) {
    const byId = new Map(results.map(item => [item.control.id, item]));
    elements.questions.innerHTML = controls.map(control => {
        const result = byId.get(control.id);
        const scores = result ? `
            <div class="scores">
                <span>обычный <b>${percent(result.baseline.quality.conceptCoverage)}</b></span>
                <span>улучшенный <b>${percent(result.enhanced.quality.conceptCoverage)}</b></span>
            </div>
            <details class="eval-details">
                <summary>Ответы и retrieval</summary>
                <p><b>Обычный:</b> ${escapeHtml(result.baseline.result.answer)}</p>
                <p><b>Улучшенный:</b> ${escapeHtml(result.enhanced.result.answer)}</p>
                <small>Rewrite: ${escapeHtml(result.enhanced.result.searchQuery)}</small>
            </details>` : "";
        return `
            <article class="question-card">
                <b>${String(control.id).padStart(2, "0")}</b>
                <div>
                    <h3>${escapeHtml(control.question)}</h3>
                    <p><strong>Ожидание:</strong> ${escapeHtml(control.expectation)}</p>
                    <p><strong>Источник:</strong> ${control.expectedSources.map(escapeHtml).join(", ")}</p>
                    ${scores}
                </div>
                <button data-id="${control.id}">Проверить →</button>
            </article>`;
    }).join("");
    elements.questions.querySelectorAll("button[data-id]").forEach(button => {
        button.addEventListener("click", () => {
            elements.question.value = controls.find(item => item.id === Number(button.dataset.id)).question;
            window.scrollTo({top: document.querySelector(".lab").offsetTop - 20, behavior: "smooth"});
            elements.question.focus();
        });
    });
}

async function compare() {
    const question = elements.question.value.trim();
    if (!question) return showToast("Введите вопрос.", true);
    let retrieval;
    try { retrieval = settings(); } catch (error) { return showToast(error.message, true); }
    setBusy(elements.compare, true, "Retrieval<br>работает…");
    elements.comparison.className = "comparison empty";
    elements.comparison.innerHTML = '<div class="empty-state">Переписываем запрос, ищем и фильтруем чанки…</div>';
    try {
        const result = await api("/api/compare", {
            method: "POST",
            body: JSON.stringify({question, ...retrieval}),
        });
        renderComparison(result);
    } catch (error) {
        elements.comparison.innerHTML = '<div class="empty-state">Сравнение не выполнено.</div>';
        showToast(error.message, true);
    } finally {
        setBusy(elements.compare, false);
    }
}

async function rebuild() {
    setBusy(elements.rebuild, true, "Индексируем…");
    try {
        const result = await api("/api/index", {method: "POST", body: "{}"});
        showToast(`Индекс готов: ${result.chunks} чанков.`);
        await loadInfo();
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.rebuild, false);
    }
}

function updateProgress(run) {
    elements.progress.classList.remove("hidden");
    elements.progressValue.textContent = `${run.completed} / ${run.total}`;
    elements.progressLabel.textContent = run.currentQuestion || (run.status === "completed" ? "Готово" : "Подготовка…");
    elements.progressBar.style.width = `${run.completed / run.total * 100}%`;
    renderQuestions(run.results);
}

function renderSummary(summary) {
    elements.summary.classList.remove("hidden");
    elements.summary.innerHTML = `
        <div><span>Понятия · обычный</span><strong>${percent(summary.baselineConceptCoverage)}</strong></div>
        <div><span>Понятия · улучшенный</span><strong>${percent(summary.enhancedConceptCoverage)}</strong></div>
        <div><span>Recall источников</span><strong>${percent(summary.baselineSourceRecall)} → ${percent(summary.enhancedSourceRecall)}</strong></div>
        <div><span>Цитирование</span><strong>${percent(summary.baselineCitationRate)} → ${percent(summary.enhancedCitationRate)}</strong></div>
        <div><span>Retention кандидатов</span><strong>${percent(summary.enhancedCandidateRetention)}</strong></div>`;
}

async function runEvaluation() {
    let retrieval;
    try { retrieval = settings(); } catch (error) { return showToast(error.message, true); }
    setBusy(elements.runEvaluation, true, "Оценка идёт…");
    elements.summary.classList.add("hidden");
    try {
        let run = await api("/api/evaluations", {method: "POST", body: JSON.stringify(retrieval)});
        updateProgress(run);
        while (run.status === "running") {
            await new Promise(resolve => window.setTimeout(resolve, 900));
            run = await api(`/api/evaluations/${run.id}`);
            updateProgress(run);
        }
        if (run.status === "failed") throw new Error(run.error || "Оценка завершилась с ошибкой.");
        renderSummary(run.summary);
        showToast("Контрольный прогон завершён.");
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.runEvaluation, false);
    }
}

async function loadInfo() {
    const info = await api("/api/info");
    elements.chatModel.textContent = info.chatModel;
    elements.embeddingModel.textContent = info.embeddingModel;
    elements.questionCount.textContent = info.controlQuestions;
    const structured = info.stats.find(item => item.strategy === "structured");
    elements.chunkCount.textContent = structured?.chunks ?? 0;
    elements.documentCount.textContent = structured?.documents ?? 0;
    elements.candidateK.value = info.defaults.candidateK;
    elements.finalK.value = info.defaults.finalK;
    elements.threshold.value = info.defaults.similarityThreshold;
}

async function initialise() {
    try {
        const [, questions] = await Promise.all([loadInfo(), api("/api/questions")]);
        controls = questions.questions;
        renderQuestions();
    } catch (error) {
        showToast(error.message, true);
    }
}

elements.compare.addEventListener("click", compare);
elements.rebuild.addEventListener("click", rebuild);
elements.runEvaluation.addEventListener("click", runEvaluation);
elements.question.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") compare();
});
initialise();
