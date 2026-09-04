(function () {
    let allDentists = []; // every dentist, active + inactive
    let staffList = [];
    let editingUserId = null; // null while the Add Staff modal is open
    let usernameTouched = false;

    const DAY_LETTERS = ["S", "M", "T", "W", "T", "F", "S"]; // Sun..Sat
    const WD_IDS = ["wdSun", "wdMon", "wdTue", "wdWed", "wdThu", "wdFri", "wdSat"]; // storage order Sun..Sat
    const DEFAULT_WORKING_DAYS = "0111110"; // Mon-Fri

    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    async function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("openAddStaffBtn").addEventListener("click", openAddModal);
        wireAddStaffModal();
        wireRoleModal();
        wireConfirmModal();
        wireTabs();
        activateTab("doctor");

        document.getElementById("dentistListContainer").addEventListener("click", handleTableAction);
        document.getElementById("listContainer").addEventListener("click", handleTableAction);

        // Both tables cross-reference dentist <-> user data, so load dentists
        // before either table renders.
        await loadDentists();
        await loadStaff();

        if (new URLSearchParams(window.location.search).get("action") === "new") {
            openAddModal();
        }
    }

    function handleTableAction(e) {
        const target = e.target.closest("[data-action]");
        if (!target) return;
        const userId = Number(target.dataset.userId);
        const action = target.dataset.action;
        if (action === "edit") openEditModal(userId);
        else if (action === "role") openRoleModal(userId);
        else if (action === "toggle") confirmToggle(userId);
    }

    // ------------------------------------------------------------------- tabs ----

    function wireTabs() {
        const tabs = document.querySelectorAll(".tab-link");
        tabs.forEach(tab => tab.addEventListener("click", () => activateTab(tab.dataset.tab)));
    }

    function activateTab(tab) {
        document.getElementById("doctorStaffTab").classList.toggle("active", tab === "doctor");
        document.getElementById("generalStaffTab").classList.toggle("active", tab === "general");
        document.getElementById("doctorTabPanel").hidden = tab !== "doctor";
        document.getElementById("generalTabPanel").hidden = tab !== "general";
        document.getElementById("doctorStaffStats").hidden = tab !== "doctor";
        document.getElementById("generalStaffStats").hidden = tab !== "general";
    }

    async function loadDentists() {
        try {
            allDentists = await Api.get("/dentists?all=true");
        } catch (err) {
            allDentists = [];
        }
    }

    function activeDentists() {
        return allDentists.filter(d => d.isActive);
    }

    // ------------------------------------------------------------- doctor staff ----

    function workingDaysCirclesHtml(workingDays) {
        const pattern = (workingDays || "0000000").split("").map(c => c === "1");
        const circles = pattern.map((isWorking, i) =>
            `<span class="working-day-circle ${isWorking ? "working" : "off"}">${DAY_LETTERS[i]}</span>`).join("");
        return `<div class="working-days-row">${circles}</div>`;
    }

    function contactCellHtml(contactNumber, email) {
        if (!contactNumber && !email) return "&mdash;";
        return (contactNumber ? `<div class="table-muted-text">${escapeHtml(contactNumber)}</div>` : "")
            + (email ? `<div class="table-muted-text"><a href="mailto:${escapeHtml(email)}">${escapeHtml(email)}</a></div>` : "");
    }

    function actionLinksHtml(user, isActive) {
        const links = [
            `<button type="button" class="link-btn" data-action="edit" data-user-id="${user.userId}">Edit</button>`,
            `<button type="button" class="link-btn" data-action="role" data-user-id="${user.userId}">Change Role</button>`,
        ];
        if (isActive) {
            links.push(`<button type="button" class="link-btn link-btn-danger" data-action="toggle" data-user-id="${user.userId}">Deactivate</button>`);
        } else {
            links.push(`<button type="button" class="link-btn" data-action="toggle" data-user-id="${user.userId}">Activate</button>`);
        }
        return `<div class="action-links">${links.join('<span class="action-sep">&middot;</span>')}</div>`;
    }

    async function renderDoctorStaff() {
        const container = document.getElementById("dentistListContainer");
        try {
            const appointments = await Api.get("/appointments").catch(() => []);

            if (allDentists.length === 0) {
                container.innerHTML = `<div class="empty-state"><div class="empty-title">No dentists found</div></div>`;
                updateDoctorStats([], 0);
                return;
            }

            const countByName = new Map();
            for (const a of appointments) {
                countByName.set(a.dentistName, (countByName.get(a.dentistName) || 0) + 1);
            }

            updateDoctorStats(allDentists, appointments.length);

            const rows = allDentists.map(d => {
                const user = staffList.find(s => s.role === "DENTIST" && s.dentistId === d.dentistId);
                return `
                <tr>
                    <td class="table-primary-text">${escapeHtml(d.dentistName)}</td>
                    <td class="table-muted-text">${user ? escapeHtml(user.username) : "&mdash;"}</td>
                    <td class="table-muted-text">${escapeHtml(d.specialty || "&mdash;")}</td>
                    <td>${contactCellHtml(d.contactNumber, d.email)}</td>
                    <td>${workingDaysCirclesHtml(d.workingDays)}</td>
                    <td>${countByName.get(d.dentistName) || 0}</td>
                    <td><span class="badge ${d.isActive ? "badge-active" : "badge-inactive"}">${d.isActive ? "Active" : "Inactive"}</span></td>
                    <td>${user ? actionLinksHtml(user, d.isActive) : "&mdash;"}</td>
                </tr>`;
            }).join("");
            container.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr>
                    <th>Name</th><th>Username</th><th>Specialty</th><th>Contact</th>
                    <th>Working Days</th><th>Appointments</th><th>Status</th><th>Actions</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load dentists</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function updateDoctorStats(dentists, appointmentCount) {
        Metrics.render(document.getElementById("doctorStaffStatsRow"), [
            { label: "total dentists", value: dentists.length },
            { label: "active", value: dentists.filter(d => d.isActive).length },
            { label: "appointments this month", value: appointmentCount },
        ]);
    }

    function updateGeneralStats(staff) {
        const counts = { ADMIN: 0, RECEPTIONIST: 0, BILLING: 0, CLINICAL_ASSISTANT: 0 };
        staff.filter(s => s.role !== "DENTIST").forEach(s => {
            if (Object.prototype.hasOwnProperty.call(counts, s.role)) counts[s.role]++;
        });
        Metrics.render(document.getElementById("generalStaffStatsRow"), [
            { label: "administrator", value: counts.ADMIN },
            { label: "front desk reception", value: counts.RECEPTIONIST },
            { label: "billing staff", value: counts.BILLING },
            { label: "clinical assistants", value: counts.CLINICAL_ASSISTANT },
        ]);
    }

    // ------------------------------------------------------------- general staff ----

    async function loadStaff() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading staff...</div>`;
        try {
            staffList = await Api.get("/staff");
            renderStaff();
            renderDoctorStaff();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load staff</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderStaff() {
        const container = document.getElementById("listContainer");
        const generalStaff = staffList.filter(s => s.role !== "DENTIST");
        updateGeneralStats(staffList);
        if (generalStaff.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="empty-title">No staff accounts found</div></div>`;
            return;
        }
        const rows = generalStaff.map(s => {
            const linkedDentist = s.assignedDentistId != null
                ? allDentists.find(d => d.dentistId === s.assignedDentistId)
                : null;
            return `
            <tr>
                <td class="table-primary-text">${escapeHtml(s.fullName)}</td>
                <td class="table-muted-text">${escapeHtml(s.username)}</td>
                <td>${escapeHtml(Shell.roleLabel(s.role))}</td>
                <td>${contactCellHtml(s.contactNumber, s.email)}</td>
                <td>${linkedDentist ? escapeHtml(linkedDentist.dentistName) : "&mdash;"}</td>
                <td><span class="badge ${s.active ? "badge-active" : "badge-inactive"}">${s.active ? "Active" : "Inactive"}</span></td>
                <td>${actionLinksHtml(s, s.active)}</td>
            </tr>`;
        }).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Contact</th><th>Assigned Dentist</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    // -------------------------------------------------------- username / Dr. prefix ----

    function firstNameSlug(fullName) {
        const stripped = (fullName || "").trim().replace(/^dr\.?\s+/i, "");
        const first = stripped.split(/\s+/)[0] || "";
        return first.toLowerCase().replace(/[^a-z]/g, "");
    }

    function suggestUsername(fullName, role) {
        const slug = firstNameSlug(fullName);
        if (!slug) return "";
        const base = role === "DENTIST" ? "dentist." + slug : slug;
        const existing = new Set(staffList.map(s => s.username));
        if (!existing.has(base)) return base;
        let n = 2;
        while (existing.has(base + n)) n++;
        return base + n;
    }

    function applyDoctorPrefix() {
        const input = document.getElementById("staffFullName");
        const value = input.value.trim();
        if (value && !/^dr\.?\s/i.test(value)) {
            input.value = "Dr. " + value;
        }
    }

    function refreshUsernameSuggestion() {
        if (usernameTouched || editingUserId != null) return;
        const role = document.getElementById("staffRole").value;
        document.getElementById("staffUsername").value =
            suggestUsername(document.getElementById("staffFullName").value, role);
    }

    // -------------------------------------------------------------- working days ----

    function workingDaysFromCheckboxes() {
        return WD_IDS.map(id => document.getElementById(id).checked ? "1" : "0").join("");
    }

    function setCheckboxesFromWorkingDays(workingDays) {
        const value = workingDays || DEFAULT_WORKING_DAYS;
        WD_IDS.forEach((id, i) => {
            document.getElementById(id).checked = value[i] === "1";
        });
    }

    // ------------------------------------------------------- role-specific fields ----

    function populateAssignedDentistSelect(selectedId) {
        const select = document.getElementById("staffAssignedDentistId");
        const dentists = activeDentists();
        select.innerHTML = dentists.length
            ? dentists.map(d => `<option value="${d.dentistId}" ${Number(selectedId) === d.dentistId ? "selected" : ""}>${escapeHtml(d.dentistName)}</option>`).join("")
            : '<option value="">No active dentists available</option>';
    }

    function updateRoleFieldsVisibility(role) {
        document.getElementById("staffSpecialtyGroup").style.display = role === "DENTIST" ? "block" : "none";
        document.getElementById("staffWorkingDaysGroup").style.display = role === "DENTIST" ? "block" : "none";
        document.getElementById("staffAssignedDentistGroup").style.display = role === "CLINICAL_ASSISTANT" ? "block" : "none";
        if (role === "CLINICAL_ASSISTANT") {
            populateAssignedDentistSelect(null);
        }
    }

    // -------------------------------------------------------- add / edit staff ----

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

    function resetModalErrors() {
        ["staffFullName,staffFullNameError", "staffUsername,staffUsernameError", "staffPassword,staffPasswordError",
            "staffContact,staffContactError", "staffEmail,staffEmailError", "staffSpecialty,staffSpecialtyError",
            "staffAssignedDentistId,staffAssignedDentistIdError"]
            .forEach(pair => {
                const [inputId, errorId] = pair.split(",");
                setFieldError(inputId, errorId, false);
            });
        document.getElementById("addStaffAlert").classList.remove("visible");
    }

    function openAddModal() {
        editingUserId = null;
        usernameTouched = false;
        document.getElementById("addStaffForm").reset();
        resetModalErrors();

        document.getElementById("staffModalTitle").textContent = "Add Staff";
        document.getElementById("addStaffSubmitBtn").textContent = "Create Staff Account";
        document.getElementById("staffRoleGroup").style.display = "block";
        document.getElementById("staffRole").value = "RECEPTIONIST";
        document.querySelector('label[for="staffPassword"]').innerHTML = 'Password <span aria-hidden="true">*</span>';
        document.getElementById("staffPasswordHint").style.display = "none";
        document.getElementById("staffPassword").type = "password";
        document.getElementById("togglePasswordBtn").textContent = "Show";
        setCheckboxesFromWorkingDays(null);
        updateRoleFieldsVisibility("RECEPTIONIST");

        document.getElementById("staffModalOverlay").classList.add("open");
        document.getElementById("staffFullName").focus();
    }

    function openEditModal(userId) {
        const user = staffList.find(s => s.userId === userId);
        if (!user) return;
        editingUserId = userId;
        usernameTouched = true;
        resetModalErrors();

        document.getElementById("staffModalTitle").textContent = "Edit Staff";
        document.getElementById("addStaffSubmitBtn").textContent = "Save Changes";
        document.getElementById("staffRoleGroup").style.display = "none";
        document.getElementById("staffFullName").value = user.fullName;
        document.getElementById("staffUsername").value = user.username;
        document.getElementById("staffPassword").value = "";
        document.getElementById("staffPassword").type = "password";
        document.getElementById("togglePasswordBtn").textContent = "Show";
        document.getElementById("staffContact").value = user.contactNumber || "";
        document.getElementById("staffEmail").value = user.email || "";
        document.querySelector('label[for="staffPassword"]').textContent = "Password";
        document.getElementById("staffPasswordHint").style.display = "block";

        updateRoleFieldsVisibility(user.role);
        if (user.role === "DENTIST") {
            const dentist = allDentists.find(d => d.dentistId === user.dentistId);
            document.getElementById("staffSpecialty").value = (dentist && dentist.specialty) || "General Dentistry";
            setCheckboxesFromWorkingDays(dentist ? dentist.workingDays : null);
        } else if (user.role === "CLINICAL_ASSISTANT") {
            populateAssignedDentistSelect(user.assignedDentistId);
        }

        document.getElementById("staffModalOverlay").classList.add("open");
        document.getElementById("staffFullName").focus();
    }

    function closeAddStaffModal() {
        document.getElementById("staffModalOverlay").classList.remove("open");
        editingUserId = null;
    }

    function wireAddStaffModal() {
        document.getElementById("closeStaffModal").addEventListener("click", closeAddStaffModal);
        document.getElementById("cancelAddStaffBtn").addEventListener("click", closeAddStaffModal);
        document.getElementById("staffModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "staffModalOverlay") closeAddStaffModal();
        });

        document.getElementById("togglePasswordBtn").addEventListener("click", () => {
            const input = document.getElementById("staffPassword");
            const btn = document.getElementById("togglePasswordBtn");
            const show = input.type === "password";
            input.type = show ? "text" : "password";
            btn.textContent = show ? "Hide" : "Show";
        });

        document.getElementById("staffRole").addEventListener("change", () => {
            const role = document.getElementById("staffRole").value;
            updateRoleFieldsVisibility(role);
            if (role === "DENTIST") applyDoctorPrefix();
            refreshUsernameSuggestion();
        });

        document.getElementById("staffFullName").addEventListener("blur", () => {
            if (editingUserId != null) return;
            if (document.getElementById("staffRole").value === "DENTIST") applyDoctorPrefix();
            refreshUsernameSuggestion();
        });

        document.getElementById("staffUsername").addEventListener("input", () => {
            usernameTouched = true;
        });

        document.getElementById("addStaffForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            resetModalErrors();

            const fullName = document.getElementById("staffFullName");
            const username = document.getElementById("staffUsername");
            const password = document.getElementById("staffPassword");
            const role = editingUserId == null
                ? document.getElementById("staffRole").value
                : staffList.find(s => s.userId === editingUserId).role;

            let valid = true;
            if (fullName.value.trim() === "") {
                setFieldError("staffFullName", "staffFullNameError", true);
                valid = false;
            }
            if (username.value.trim() === "") {
                setFieldError("staffUsername", "staffUsernameError", true);
                valid = false;
            }
            if (editingUserId == null && password.value.trim() === "") {
                setFieldError("staffPassword", "staffPasswordError", true);
                valid = false;
            }
            if (role === "CLINICAL_ASSISTANT" && !document.getElementById("staffAssignedDentistId").value) {
                setFieldError("staffAssignedDentistId", "staffAssignedDentistIdError", true);
                valid = false;
            }
            if (!valid) return;

            const submitBtn = document.getElementById("addStaffSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = editingUserId == null ? "Creating..." : "Saving...";
            const alertEl = document.getElementById("addStaffAlert");

            const payload = {
                fullName: fullName.value.trim(),
                username: username.value.trim(),
                password: password.value,
                contactNumber: document.getElementById("staffContact").value.trim(),
                email: document.getElementById("staffEmail").value.trim(),
            };
            if (role === "DENTIST") {
                payload.specialty = document.getElementById("staffSpecialty").value;
                payload.workingDays = workingDaysFromCheckboxes();
            }
            if (role === "CLINICAL_ASSISTANT") {
                payload.assignedDentistId = document.getElementById("staffAssignedDentistId").value;
            }

            try {
                if (editingUserId == null) {
                    payload.role = role;
                    await Api.post("/staff", payload);
                    Toast.success("Staff account created successfully.");
                } else {
                    const params = new URLSearchParams();
                    Object.entries(payload).forEach(([key, value]) => {
                        if (value !== null && value !== undefined && value !== "") params.set(key, value);
                    });
                    await Api.put(`/staff/${editingUserId}?${params.toString()}`);
                    Toast.success("Staff account updated successfully.");
                }
                closeAddStaffModal();
                await loadStaff();
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = editingUserId == null ? "Create Staff Account" : "Save Changes";
            }
        });
    }

    // ------------------------------------------------------------ change role ----

    let roleTargetUserId = null;

    function dentistOptions(selectedId) {
        return activeDentists().map(d =>
            `<option value="${d.dentistId}" ${Number(selectedId) === d.dentistId ? "selected" : ""}>${escapeHtml(d.dentistName)}</option>`
        ).join("");
    }

    /**
     * DENTIST shows the "Dentist" field; CLINICAL_ASSISTANT shows the
     * "Assigned Dentist" field - the two are mutually exclusive per role,
     * mirroring the backend's dentist_id vs assigned_dentist_id split.
     */
    function toggleDentistFields(roleSelectEl, dentistGroupEl, assignedGroupEl, selectedDentistId, selectedAssignedId) {
        const role = roleSelectEl.value;

        function fill(groupEl, selectedId) {
            const select = groupEl.querySelector("select");
            select.innerHTML = activeDentists().length
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
