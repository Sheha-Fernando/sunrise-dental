(function () {
    let activeDentists = [];
    let staffList = [];

    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("openAddStaffBtn").addEventListener("click", openAddStaffModal);
        wireAddStaffModal();
        wireRoleModal();
        wireConfirmModal();

        loadDentists();
        loadStaff();

        if (new URLSearchParams(window.location.search).get("action") === "new") {
            openAddStaffModal();
        }
    }

    async function loadDentists() {
        try {
            activeDentists = await Api.get("/dentists");
        } catch (err) {
            activeDentists = [];
        }
    }

    function dentistOptions(selectedId) {
        return activeDentists.map(d =>
            `<option value="${d.dentistId}" ${Number(selectedId) === d.dentistId ? "selected" : ""}>${escapeHtml(d.dentistName)}</option>`
        ).join("");
    }

    async function loadStaff() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading staff...</div>`;
        try {
            staffList = await Api.get("/staff");
            renderStaff();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load staff</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderStaff() {
        const container = document.getElementById("listContainer");
        if (staffList.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="empty-title">No staff accounts found</div></div>`;
            return;
        }
        const rows = staffList.map(s => {
            const linkedDentistId = s.dentistId != null ? s.dentistId : s.assignedDentistId;
            const linkedDentist = linkedDentistId != null
                ? activeDentists.find(d => d.dentistId === linkedDentistId)
                : null;
            return `
            <tr>
                <td class="table-primary-text">${escapeHtml(s.fullName)}</td>
                <td class="table-muted-text">${escapeHtml(s.username)}</td>
                <td>${escapeHtml(Shell.roleLabel(s.role))}</td>
                <td>${linkedDentist ? escapeHtml(linkedDentist.dentistName) : "&mdash;"}</td>
                <td><span class="badge ${s.active ? "badge-active" : "badge-inactive"}">${s.active ? "Active" : "Inactive"}</span></td>
                <td>
                    <button type="button" class="btn btn-secondary btn-sm" data-action="role" data-id="${s.userId}">Change Role</button>
                    <button type="button" class="btn ${s.active ? "btn-danger" : "btn-secondary"} btn-sm" data-action="toggle" data-id="${s.userId}">
                        ${s.active ? "Deactivate" : "Activate"}
                    </button>
                </td>
            </tr>`;
        }).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Assigned Dentist</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;

        container.querySelectorAll('[data-action="role"]').forEach(btn =>
            btn.addEventListener("click", () => openRoleModal(Number(btn.dataset.id))));
        container.querySelectorAll('[data-action="toggle"]').forEach(btn =>
            btn.addEventListener("click", () => confirmToggle(Number(btn.dataset.id))));
    }

    // ------------------------------------------------------------- add staff ----

    /**
     * DENTIST shows the "Dentist" field; CLINICAL_ASSISTANT shows the
     * "Assigned Dentist" field - the two are mutually exclusive per role,
     * mirroring the backend's dentist_id vs assigned_dentist_id split.
     */
    function toggleDentistFields(roleSelectEl, dentistGroupEl, assignedGroupEl, selectedDentistId, selectedAssignedId) {
        const role = roleSelectEl.value;

        function fill(groupEl, selectedId) {
            const select = groupEl.querySelector("select");
            select.innerHTML = activeDentists.length
                ? dentistOptions(selectedId)
                : '<option value="">No active dentists available</option>';
        }

        if (role === "DENTIST") {
            dentistGroupEl.style.display = "block";
            assignedGroupEl.style.display = "none";
            fill(dentistGroupEl, selectedDentistId);
        } else if (role === "CLINICAL_ASSISTANT") {
            dentistGroupEl.style.display = "none";
            assignedGroupEl.style.display = "block";
            fill(assignedGroupEl, selectedAssignedId);
        } else {
            dentistGroupEl.style.display = "none";
            assignedGroupEl.style.display = "none";
        }
    }

    function openAddStaffModal() {
        document.getElementById("staffModalOverlay").classList.add("open");
        document.getElementById("staffFullName").focus();
    }

    function closeAddStaffModal() {
        document.getElementById("staffModalOverlay").classList.remove("open");
        document.getElementById("addStaffForm").reset();
        document.getElementById("addStaffAlert").classList.remove("visible");
        document.getElementById("staffDentistGroup").style.display = "none";
        document.getElementById("staffAssignedDentistGroup").style.display = "none";
    }

    function wireAddStaffModal() {
        document.getElementById("closeStaffModal").addEventListener("click", closeAddStaffModal);
        document.getElementById("cancelAddStaffBtn").addEventListener("click", closeAddStaffModal);
        document.getElementById("staffModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "staffModalOverlay") closeAddStaffModal();
        });

        const roleSelect = document.getElementById("staffRole");
        const dentistGroup = document.getElementById("staffDentistGroup");
        const assignedGroup = document.getElementById("staffAssignedDentistGroup");
        roleSelect.addEventListener("change", () =>
            toggleDentistFields(roleSelect, dentistGroup, assignedGroup, null, null));

        document.getElementById("addStaffForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            const fullName = document.getElementById("staffFullName");
            const username = document.getElementById("staffUsername");
            const password = document.getElementById("staffPassword");
            const role = roleSelect.value;
            const dentistId = document.getElementById("staffDentistId").value;
            const assignedDentistId = document.getElementById("staffAssignedDentistId").value;

            let valid = true;
            [
                { input: fullName, error: document.getElementById("staffFullNameError") },
                { input: username, error: document.getElementById("staffUsernameError") },
                { input: password, error: document.getElementById("staffPasswordError") },
            ].forEach(f => {
                if (f.input.value.trim() === "") {
                    f.input.setAttribute("aria-invalid", "true");
                    f.error.classList.add("visible");
                    valid = false;
                } else {
                    f.input.removeAttribute("aria-invalid");
                    f.error.classList.remove("visible");
                }
            });
            if (role === "DENTIST" && !dentistId) {
                document.getElementById("staffDentistIdError").classList.add("visible");
                valid = false;
            } else {
                document.getElementById("staffDentistIdError").classList.remove("visible");
            }
            if (role === "CLINICAL_ASSISTANT" && !assignedDentistId) {
                document.getElementById("staffAssignedDentistIdError").classList.add("visible");
                valid = false;
            } else {
                document.getElementById("staffAssignedDentistIdError").classList.remove("visible");
            }
            if (!valid) return;

            const submitBtn = document.getElementById("addStaffSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = "Creating...";
            const alertEl = document.getElementById("addStaffAlert");
            alertEl.classList.remove("visible");

            try {
                await Api.post("/staff", {
                    fullName: fullName.value.trim(),
                    username: username.value.trim(),
                    password: password.value,
                    role: role,
                    dentistId: role === "DENTIST" ? dentistId : "",
                    assignedDentistId: role === "CLINICAL_ASSISTANT" ? assignedDentistId : "",
                });
                closeAddStaffModal();
                Toast.success("Staff account created successfully.");
                loadStaff();
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "Create Staff Account";
            }
        });
    }

    // ------------------------------------------------------------ change role ----

    let roleTargetUserId = null;

    function openRoleModal(userId) {
        const staff = staffList.find(s => s.userId === userId);
        if (!staff) return;
        roleTargetUserId = userId;
        const roleSelect = document.getElementById("roleSelect");
        const dentistGroup = document.getElementById("roleDentistGroup");
        const assignedGroup = document.getElementById("roleAssignedDentistGroup");
        roleSelect.value = staff.role;
        toggleDentistFields(roleSelect, dentistGroup, assignedGroup, staff.dentistId, staff.assignedDentistId);
        document.getElementById("roleAlert").classList.remove("visible");
        document.getElementById("roleModalOverlay").classList.add("open");
    }

    function closeRoleModal() {
        document.getElementById("roleModalOverlay").classList.remove("open");
        roleTargetUserId = null;
    }

    function wireRoleModal() {
        document.getElementById("closeRoleModal").addEventListener("click", closeRoleModal);
        document.getElementById("cancelRoleBtn").addEventListener("click", closeRoleModal);
        document.getElementById("roleModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "roleModalOverlay") closeRoleModal();
        });

        const roleSelect = document.getElementById("roleSelect");
        const dentistGroup = document.getElementById("roleDentistGroup");
        const assignedGroup = document.getElementById("roleAssignedDentistGroup");
        roleSelect.addEventListener("change", () =>
            toggleDentistFields(roleSelect, dentistGroup, assignedGroup, null, null));

        document.getElementById("roleForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            if (!roleTargetUserId) return;
            const role = roleSelect.value;
            const dentistId = document.getElementById("roleDentistId").value;
            const assignedDentistId = document.getElementById("roleAssignedDentistId").value;
            const submitBtn = document.getElementById("roleSubmitBtn");
            const alertEl = document.getElementById("roleAlert");
            submitBtn.disabled = true;
            alertEl.classList.remove("visible");

            try {
                const params = new URLSearchParams({ role });
                if (role === "DENTIST" && dentistId) {
                    params.set("dentistId", dentistId);
                }
                if (role === "CLINICAL_ASSISTANT" && assignedDentistId) {
                    params.set("assignedDentistId", assignedDentistId);
                }
                await Api.put(`/staff/${roleTargetUserId}?${params.toString()}`);
                closeRoleModal();
                Toast.success("Staff account updated successfully.");
                loadStaff();
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
            }
        });
    }

    // ----------------------------------------------------------- deactivate ----

    function confirmToggle(userId) {
        const staff = staffList.find(s => s.userId === userId);
        if (!staff) return;
        const action = staff.active ? "Deactivate" : "Activate";
        showConfirm(
            `${action} staff member?`,
            staff.active
                ? `${staff.fullName} will no longer be able to sign in.`
                : `${staff.fullName} will be able to sign in again.`,
            async () => {
                try {
                    await Api.put(`/staff/${userId}?active=${!staff.active}`);
                    Toast.success("Staff account updated successfully.");
                    loadStaff();
                } catch (err) {
                    document.getElementById("staffAlert").textContent = err.message;
                    document.getElementById("staffAlert").classList.add("visible");
                }
            }
        );
    }

    let confirmCallback = null;

    function showConfirm(title, message, onConfirm) {
        document.getElementById("confirmTitle").textContent = title;
        document.getElementById("confirmMessage").textContent = message;
        confirmCallback = onConfirm;
        document.getElementById("confirmModalOverlay").classList.add("open");
    }

    function wireConfirmModal() {
        document.getElementById("confirmCancelBtn").addEventListener("click", () => {
            document.getElementById("confirmModalOverlay").classList.remove("open");
            confirmCallback = null;
        });
        document.getElementById("confirmOkBtn").addEventListener("click", () => {
            document.getElementById("confirmModalOverlay").classList.remove("open");
            if (confirmCallback) confirmCallback();
            confirmCallback = null;
        });
    }
})();
