package app.fuelog.service;

import app.fuelog.dto.MaintenanceDtos.*;
import app.fuelog.repository.MaintenanceRepository.PlanRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaintenanceRiskTests {
    @Test
    void calendarHalfCycleUsesActualMonthLengthAndRoundsUpOddDays() {
        var regular = plan("2025-01-31");
        assertEquals(RiskLevel.NORMAL, MaintenanceService.risk(regular, LocalDate.parse("2025-02-27"), null));
        assertEquals(RiskLevel.OVERDUE, MaintenanceService.risk(regular, LocalDate.parse("2025-02-28"), null));
        assertEquals(RiskLevel.OVERDUE, MaintenanceService.risk(regular, LocalDate.parse("2025-03-13"), null));
        assertEquals(RiskLevel.DANGER, MaintenanceService.risk(regular, LocalDate.parse("2025-03-14"), null));
        var leap = plan("2024-01-31");
        assertEquals(RiskLevel.OVERDUE, MaintenanceService.risk(leap, LocalDate.parse("2024-03-14"), null));
        assertEquals(RiskLevel.DANGER, MaintenanceService.risk(leap, LocalDate.parse("2024-03-15"), null));
        assertEquals(RiskLevel.DANGER, MaintenanceService.risk(leap, LocalDate.parse("2024-02-01"), new BigDecimal("1500")));
    }

    private PlanRow plan(String date) {
        LocalDate last = LocalDate.parse(date);
        UUID id = UUID.randomUUID();
        return new PlanRow(id, id, id, "oil", true, true, Basis.USER, null,
                new BigDecimal("1000"), 1, 1, last, BigDecimal.ZERO, last.plusMonths(1),
                new BigDecimal("1000"), null, null, null, null);
    }
}
