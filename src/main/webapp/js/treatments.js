(function () {
    let session = null;
    let allTreatments = [];
    let editingTreatmentId = null; // null while the Add Treatment modal is open
    let pendingDeactivateId = null;

    document.addEventListener("shell:ready", (e) => {
        session = e.detail;
        init();
    });

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function isAdmin() {
        return session.role === "ADMIN";
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        if (isAdmin()) {
            document.getElementById("headerActions").innerHTML =
                `<button type="button" class="btn btn-primary" id="openAddTreatmentBtn">+ Add Treatment</button>`;
            document.getElementById("openAddTreatmentBtn").addEventListener("click", openAddModal);
            wireTreatmentModal();
            wireDeactivateModal();
        }

        document.getElementById("listContainer").addEventListener("click", (e) => {
            const target = e.target.closest("[data-treatment-action]");
            if (!target) return;
            const id = Number(target.dataset.treatmentId);
            const action = target.dataset.treatmentAction;
            if (action === "edit") openEditModal(id);
            else if (action === "deactivate") openDeactivateConfirm(id);
            else if (action === "activate") setActiveStatus(id, true);
        });

        load();
    }

    async function load() {
        const container = document.getElementById("listContainer");
        try {
            allTreatments = await Api.get("/treatments?all=true");
            renderTable();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load treatments</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderTable() {
        const container = document.getElementById("listContainer");
        if (allTreatments.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="empty-title">No treatments found</div></div>`;
            return;
        }
        const rows = allTreatments.map(t => {
            const statusBadge = t.isActive
                ? `<span class="badge badge-active">Active</span>`
                : `<span class="badge badge-inactive">Inactive</span>`;
            let actions = "&mdash;";
            if (isAdmin()) {
                const links = [`<button type="button" class="link-btn" data-treatment-action="edit" data-treatment-id="${t.treatmentId}">Edit</button>`];
                if (t.isActive) {
                    links.push(`<button type="button" class="link-btn link-btn-danger" data-treatment-action="deactivate" data-treatment-id="${t.treatmentId}">Deactivate</button>`);
                } else {
                    links.push(`<button type="button" class="link-btn" data-treatment-action="activate" data-treatment-id="${t.treatmentId}">Activate</button>`);
                }
                actions = `<div class="action-links">${links.join('<span class="action-sep">&middot;</span>')}</div>`;
            }
            return `
                <tr>
                    <td class="table-primary-text">${escapeHtml(t.treatmentName)}</td>
                    <td>${Fmt.currency(t.cost)}</td>
                    <td>${statusBadge}</td>
                    <td>${actions}</td>
                </tr>`;
        }).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Treatment</th><th>Price</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    // --- Add / Edit modal -------------------------------------------------------

    function showAlert(message) {
        const alertEl = document.getElementById("treatmentAlert");
        alertEl.textContent = message;
        alertEl.classList.add("visible");
    }

    function hideAlert() {
        document.getElementById("treatmentAlert").classList.remove("visible");
    }

    function setFieldError(inputId, errorId, show) {
        const input = document.getElementById(inputId);
        const error = document.getElementById(errorId);
        if (show) {
            input.setAttribute("aria-invalid", "true");
            error.classList.add("visible");
        } else {
            input.removeAttribute("aria-invalid");
            error.classList.remove("visible");
        }
    }

    function openAddModal() {
        editingTreatmentId = null;
        document.getElementById("treatmentModalTitle").textContent = "Add Treatment";
        document.getElementById("treatmentSubmitBtn").textContent = "Add Treatment";
        document.getElementById("treatmentName").value = "";
        document.getElementById("treatmentCost").value = "";
        document.getElementById("treatmentStatusGroup").style.display = "none";
        document.getElementById("treatmentAddNote").style.display = "block";
        hideAlert();
        setFieldError("treatmentName", "treatmentNameError", false);
        setFieldError("treatmentCost", "treatmentCostError", false);
        document.getElementById("treatmentModalOverlay").classList.add("open");
        document.getElementById("treatmentName").focus();
    }

    function openEditModal(treatmentId) {
        const treatment = allTreatments.find(t => t.treatmentId === treatmentId);
        if (!treatment) return;
        editingTreatmentId = treatmentId;
        document.getElementById("treatmentModalTitle").textContent = "Edit Treatment";
        document.getElementById("treatmentSubmitBtn").textContent = "Save Changes";
        document.getElementById("treatmentName").value = treatment.treatmentName;
        document.getElementById("treatmentCost").value = Number(treatment.cost).toFixed(2);
        document.getElementById("treatmentStatus").value = String(treatment.isActive);
        document.getElementById("treatmentStatusGroup").style.display = "block";
        document.getElementById("treatmentAddNote").style.display = "none";
        hideAlert();
        setFieldError("treatmentName", "treatmentNameError", false);
        setFieldError("treatmentCost", "treatmentCostError", false);
        document.getElementById("treatmentModalOverlay").classList.add("open");
        document.getElementById("treatmentName").focus();
    }

    function closeTreatmentModal() {
        document.getElementById("treatmentModalOverlay").classList.remove("open");
    }

    function parsePriceInput(raw) {
        // Accept "2,500.00" or "2500" - strip thousands separators before validating/sending.
        return raw.trim().replace(/,/g, "");
    }

    function wireTreatmentModal() {
        document.getElementById("closeTreatmentModal").addEventListener("click", closeTreatmentModal);
        document.getElementById("cancelTreatmentBtn").addEventListener("click", closeTreatmentModal);
        document.getElementById("treatmentModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "treatmentModalOverlay") closeTreatmentModal();
        });

        document.getElementById("treatmentForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            hideAlert();

            const nameInput = document.getElementById("treatmentName");
            const costInput = document.getElementById("treatmentCost");
            const name = nameInput.value.trim();
            const cost = parsePriceInput(costInput.value);

            let valid = true;
            if (!name) {
                setFieldError("treatmentName", "treatmentNameError", true);
                valid = false;
            } else {
                setFieldError("treatmentName", "treatmentNameError", false);
            }
            if (!cost || isNaN(Number(cost)) || Number(cost) <= 0) {
                setFieldError("treatmentCost", "treatmentCostError", true);
                valid = false;
            } else {
                setFieldError("treatmentCost", "treatmentCostError", false);
            }
            if (!valid) return;

            const submitBtn = document.getElementById("treatmentSubmitBtn");
            submitBtn.disabled = true;

            try {
                if (editingTreatmentId == null) {
                    submitBtn.textContent = "Adding...";
                    await Api.post("/treatments", { treatmentName: name, cost });
                    closeTreatmentModal();
                    Toast.success("Treatment added successfully.");
                } else {
                    submitBtn.textContent = "Saving...";
                    const isActive = document.getElementById("treatmentStatus").value;
                    const before = allTreatments.find(t => t.treatmentId === editingTreatmentId);
                    const result = await Api.put(`/treatments/${editingTreatmentId}`
                        + `?treatmentName=${encodeURIComponent(name)}`
                        + `&cost=${encodeURIComponent(cost)}`
                        + `&isActive=${encodeURIComponent(isActive)}`);
                    closeTreatmentModal();
                    if (before && Number(before.cost) !== Number(result.treatment.cost)) {
                        Toast.success(`Treatment price updated successfully. `
                            + `${name} — ${Fmt.currency(before.cost)} → ${Fmt.currency(result.treatment.cost)}`);
                    } else {
                        Toast.success("Treatment updated successfully.");
                    }
                }
                await load();
            } catch (err) {
                showAlert(err.message || "Unable to save treatment right now. Please try again.");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = editingTreatmentId == null ? "Add Treatment" : "Save Changes";
            }
        });
    }

    // --- Deactivate confirmation -------------------------------------------------

    function openDeactivateConfirm(treatmentId) {
        const treatment = allTreatments.find(t => t.treatmentId === treatmentId);
        if (!treatment) return;
        pendingDeactivateId = treatmentId;
        document.getElementById("deactivateModalMessage").innerHTML =
            `Are you sure you want to deactivate <strong>${escapeHtml(treatment.treatmentName)}</strong>?`
            + `<br><br>Deactivated treatments cannot be selected for new appointments.`;
        document.getElementById("deactivateModalOverlay").classList.add("open");
    }

    function closeDeactivateConfirm() {
        document.getElementById("deactivateModalOverlay").classList.remove("open");
        pendingDeactivateId = null;
    }

    function wireDeactivateModal() {
        document.getElementById("cancelDeactivateBtn").addEventListener("click", closeDeactivateConfirm);
        document.getElementById("deactivateModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "deactivateModalOverlay") closeDeactivateConfirm();
        });
        document.getElementById("confirmDeactivateBtn").addEventListener("click", async () => {
            if (pendingDeactivateId == null) return;
            const id = pendingDeactivateId;
            closeDeactivateConfirm();
            await setActiveStatus(id, false);
        });
    }

    async function setActiveStatus(treatmentId, active) {
        const treatment = allTreatments.find(t => t.treatmentId === treatmentId);
        if (!treatment) return;
        try {
            await Api.put(`/treatments/${treatmentId}`
                + `?treatmentName=${encodeURIComponent(treatment.treatmentName)}`
                + `&cost=${encodeURIComponent(treatment.cost)}`
                + `&isActive=${active}`);
            Toast.success(active ? "Treatment activated successfully." : "Treatment deactivated successfully.");
            await load();
        } catch (err) {
            Toast.error(err.message || "Unable to update treatment right now. Please try again.");
        }
    }
})();
