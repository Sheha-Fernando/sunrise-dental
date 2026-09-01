(function () {
    const DAY_NAMES_SHORT = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const DAY_NAMES_FULL = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
    const SLOT_MINUTES = 30; // matches the clinic's 30-minute appointment slot grid used elsewhere

    let allDentists = [];
    let appointmentsForDate = []; // every non-cancelled appointment on the currently viewed date, all dentists

    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function todayIso() {
        return new Date().toISOString().split("T")[0];
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("viewDate").value = todayIso();

        document.getElementById("filterSearch").addEventListener("input", renderFiltered);
        document.getElementById("filterWorkingStatus").addEventListener("change", renderFiltered);
        document.getElementById("filterSpecialty").addEventListener("change", renderFiltered);
        document.getElementById("viewDate").addEventListener("change", load);
        document.getElementById("clearFiltersBtn").addEventListener("click", () => {
            document.getElementById("filterSearch").value = "";
            document.getElementById("filterWorkingStatus").value = "";
            document.getElementById("filterSpecialty").value = "";
            document.getElementById("viewDate").value = todayIso();
            load();
        });
        document.getElementById("closeScheduleModal").addEventListener("click", closeScheduleModal);
        document.getElementById("scheduleModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "scheduleModalOverlay") closeScheduleModal();
        });

        load();
    }

    // --- Working days / availability, derived only from real stored data ----

    function formatWorkingDays(workingDays) {
        if (!workingDays) return "Not scheduled";
        const isOn = workingDays.split("").map(c => c === "1");
        if (isOn.every(Boolean)) return "Every day";
        if (isOn.every(v => !v)) return "Not scheduled";

        const onIndices = isOn.map((v, i) => (v ? i : -1)).filter(i => i >= 0);
        const isContiguous = onIndices.every((v, i) => i === 0 || v === onIndices[i - 1] + 1);
        if (isContiguous && onIndices.length > 2) {
            return `${DAY_NAMES_FULL[onIndices[0]]} – ${DAY_NAMES_FULL[onIndices[onIndices.length - 1]]}`;
        }
        return onIndices.map(i => DAY_NAMES_SHORT[i]).join(", ");
    }

    function isWorkingOn(workingDays, isoDate) {
        if (!workingDays) return false;
        const [y, m, d] = isoDate.split("-").map(Number);
        const dayOfWeek = new Date(y, m - 1, d).getDay(); // 0=Sun..6=Sat, matches the stored string's index order
        return workingDays.charAt(dayOfWeek) === "1";
    }

    function timeToMinutes(hhmmss) {
        const [h, m] = hhmmss.split(":").map(Number);
        return h * 60 + m;
    }

    /**
     * Real-time "Busy" is only meaningful for the current date/time - for any
     * other viewed date we fall back to whether the dentist already has a
     * booking that day, rather than fabricating a live status.
     */
    function computeAvailability(dentist, viewDateIso, dentistAppointments) {
        const workingToday = isWorkingOn(dentist.workingDays, viewDateIso);
        if (!workingToday) {
            return { label: "Not Working", badgeClass: "badge badge-inactive" };
        }
        if (viewDateIso === todayIso()) {
            const nowMinutes = new Date().getHours() * 60 + new Date().getMinutes();
            const isBusy = dentistAppointments.some(a => {
                if (a.status !== "SCHEDULED") return false;
                const start = timeToMinutes(a.appointmentTime);
                return nowMinutes >= start && nowMinutes < start + SLOT_MINUTES;
            });
            return isBusy
                ? { label: "Busy", badgeClass: "badge badge-scheduled" }
                : { label: "Available", badgeClass: "badge badge-active" };
        }
        const hasBooking = dentistAppointments.length > 0;
        return hasBooking
            ? { label: "Scheduled", badgeClass: "badge badge-scheduled" }
            : { label: "Available", badgeClass: "badge badge-active" };
    }

    // --- Load / render -----------------------------------------------------

    async function load() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading dentists...</div>`;
        const viewDate = document.getElementById("viewDate").value || todayIso();
        document.getElementById("viewDateLabel").textContent =
            (viewDate === todayIso() ? "Today — " : "") + Fmt.weekdayDate(viewDate);

        try {
            const [dentists, appointments] = await Promise.all([
                Api.get("/dentists"),
                Api.get("/appointments?date=" + encodeURIComponent(viewDate)),
            ]);
            allDentists = dentists;
            appointmentsForDate = appointments.filter(a => a.status !== "CANCELLED");
            populateSpecialtyFilter(dentists);
            renderFiltered();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load dentist availability</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
                <button type="button" class="btn btn-secondary" style="margin-top:0.9rem;" id="retryLoadBtn">Retry</button>
            </div>`;
            const retryBtn = document.getElementById("retryLoadBtn");
            if (retryBtn) retryBtn.addEventListener("click", load);
        }
    }

    function populateSpecialtyFilter(dentists) {
        const select = document.getElementById("filterSpecialty");
        const current = select.value;
        const specialties = [...new Set(dentists.map(d => d.specialty).filter(Boolean))].sort();
        select.innerHTML = '<option value="">All specialties</option>' +
            specialties.map(s => `<option value="${escapeHtml(s)}">${escapeHtml(s)}</option>`).join("");
        select.value = specialties.includes(current) ? current : "";
    }

    function renderFiltered() {
        if (allDentists.length === 0) {
            document.getElementById("listContainer").innerHTML =
                `<div class="empty-state"><div class="empty-title">No active dentists found</div></div>`;
            return;
        }

        const viewDate = document.getElementById("viewDate").value || todayIso();
        const search = document.getElementById("filterSearch").value.trim().toLowerCase();
        const workingStatus = document.getElementById("filterWorkingStatus").value;
        const specialty = document.getElementById("filterSpecialty").value;

        let rows = allDentists.map(d => {
            const dentistAppointments = appointmentsForDate.filter(a => a.dentistId === d.dentistId);
            const workingToday = isWorkingOn(d.workingDays, viewDate);
            const availability = computeAvailability(d, viewDate, dentistAppointments);
            return { dentist: d, workingToday, availability, appointmentCount: dentistAppointments.length };
        });

        if (search) {
            rows = rows.filter(r =>
                r.dentist.dentistName.toLowerCase().includes(search) ||
                (r.dentist.specialty || "").toLowerCase().includes(search));
        }
        if (workingStatus === "working") {
            rows = rows.filter(r => r.workingToday);
        } else if (workingStatus === "notWorking") {
            rows = rows.filter(r => !r.workingToday);
        }
        if (specialty) {
            rows = rows.filter(r => r.dentist.specialty === specialty);
        }

        renderTable(rows, viewDate);
    }

    function renderTable(rows, viewDate) {
        const container = document.getElementById("listContainer");
        if (rows.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No dentists match your filters</div>
                <div class="empty-desc">Try adjusting your search or filters.</div>
            </div>`;
            return;
        }

        const tableRows = rows.map(r => {
            const d = r.dentist;
            return `
            <tr>
                <td class="table-primary-text">${escapeHtml(d.dentistName)}</td>
                <td class="table-muted-text">${escapeHtml(d.specialty || "—")}</td>
                <td>
                    <div class="table-muted-text">${escapeHtml(d.contactNumber || "—")}</div>
                    ${d.email ? `<div class="table-muted-text"><a href="mailto:${escapeHtml(d.email)}">${escapeHtml(d.email)}</a></div>` : ""}
                </td>
                <td class="table-muted-text">${escapeHtml(formatWorkingDays(d.workingDays))}</td>
                <td><span class="${r.workingToday ? "badge badge-active" : "badge badge-inactive"}">${r.workingToday ? "Working" : "Not Working"}</span></td>
                <td><span class="${r.availability.badgeClass}">${r.availability.label}</span></td>
                <td><button type="button" class="link-btn" data-action="view-schedule" data-dentist-id="${d.dentistId}">View Schedule</button></td>
            </tr>`;
        }).join("");

        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr>
                <th>Dentist</th><th>Specialty</th><th>Contact</th><th>Working Days</th>
                <th>Today</th><th>Availability</th><th>Actions</th>
            </tr></thead>
            <tbody>${tableRows}</tbody>
        </table></div>`;

        container.querySelectorAll('[data-action="view-schedule"]').forEach(btn => {
            btn.addEventListener("click", () => openScheduleModal(Number(btn.dataset.dentistId), viewDate));
        });
    }

    // --- Read-only schedule modal (reuses already-loaded appointment data) ---

    function openScheduleModal(dentistId, viewDate) {
        const dentist = allDentists.find(d => d.dentistId === dentistId);
        if (!dentist) return;

        document.getElementById("scheduleModalTitle").textContent = dentist.dentistName;
        document.getElementById("scheduleModalSubtitle").textContent = Fmt.weekdayDate(viewDate);

        const schedule = appointmentsForDate
            .filter(a => a.dentistId === dentistId)
            .sort((a, b) => a.appointmentTime.localeCompare(b.appointmentTime));

        const body = document.getElementById("scheduleModalBody");
        if (schedule.length === 0) {
            body.innerHTML = `<div class="empty-state">
                <div class="empty-title">No appointments</div>
                <div class="empty-desc">This dentist has no appointments scheduled for this date.</div>
            </div>`;
        } else {
            const rows = schedule.map(a => `
                <tr>
                    <td class="table-primary-text">${Fmt.time(a.appointmentTime)}</td>
                    <td>${escapeHtml(a.patientName)}</td>
                    <td class="table-muted-text">${escapeHtml(a.treatmentType)}</td>
                    <td><span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span></td>
                </tr>`).join("");
            body.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Time</th><th>Patient</th><th>Treatment</th><th>Status</th></tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        }

        document.getElementById("scheduleModalOverlay").classList.add("open");
    }

    function closeScheduleModal() {
        document.getElementById("scheduleModalOverlay").classList.remove("open");
    }
})();
