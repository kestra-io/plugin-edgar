package io.kestra.plugin.edgar.xbrl;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Fact {
    @Schema(title = "Taxonomy", description = "For example `us-gaap`, `ifrs-full` or `dei`.")
    String taxonomy;

    @Schema(title = "Concept", description = "For example `Revenues` or `NetIncomeLoss`.")
    String concept;

    @Schema(title = "Unit", description = "For example `USD`, `shares` or `USD/shares`.")
    String unit;

    @Schema(title = "Value", description = "As reported, without rounding.")
    BigDecimal value;

    @Schema(title = "Start of the period", description = """
        Empty for values at a point in time, such as shares outstanding.""")
    LocalDate start;

    @Schema(title = "End of the period, or the date of a point-in-time value")
    LocalDate end;

    @Schema(title = "Fiscal year of the filing that reported the value")
    Integer fiscalYear;

    @Schema(title = "Fiscal period of the filing that reported the value", description = """
        `FY`, `Q1`, `Q2`, `Q3` or `Q4`.""")
    String fiscalPeriod;

    @Schema(title = "Form of the filing that reported the value", description = "For example `10-K` or `10-Q`.")
    String form;

    @Schema(title = "Date the reporting filing was made")
    LocalDate filed;

    @Schema(title = "Accession number of the reporting filing")
    String accessionNumber;

    @Schema(
        title = "Calendar frame",
        description = """
            Set by SEC on the one value it keeps per concept, unit and calendar period, for example `CY2026Q2`; empty for \
            values also reported elsewhere."""
    )
    String frame;
}
