package dev.aegis.contracts.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA model of one observed fact. The value is stored in the column of its kind, so an amount keeps decimal
 * precision and an explicit currency instead of being flattened into text.
 */
@Entity
@Table(name = "contract_facts")
public class ContractFactEntity {
    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "fact_type", nullable = false, length = 30)
    private String factType;

    @Column(name = "value_kind", nullable = false, length = 10)
    private String valueKind;

    @Column(name = "value_text", length = 500)
    private String valueText;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "value_amount", precision = 19, scale = 4)
    private BigDecimal valueAmount;

    @Column(name = "value_currency", length = 3)
    private String valueCurrency;

    @Column(name = "value_period", length = 30)
    private String valuePeriod;

    @Column(name = "value_flag")
    private Boolean valueFlag;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "review_state", nullable = false, length = 12)
    private String reviewState;

    @Column(nullable = false, length = 10)
    private String origin;

    private Double confidence;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "source_quote", length = 500)
    private String sourceQuote;

    @Column(name = "run_ref", length = 100)
    private String runRef;

    @Column(name = "observed_by")
    private UUID observedBy;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "corrects")
    private UUID corrects;

    @Column(nullable = false, length = 1000)
    private String note;

    protected ContractFactEntity() {
    }

    ContractFactEntity(UUID id, UUID contractId) {
        this.id = id;
        this.contractId = contractId;
    }

    UUID getId() {
        return id;
    }

    UUID getContractId() {
        return contractId;
    }

    String getFactType() {
        return factType;
    }

    void setFactType(String factType) {
        this.factType = factType;
    }

    String getValueKind() {
        return valueKind;
    }

    void setValueKind(String valueKind) {
        this.valueKind = valueKind;
    }

    String getValueText() {
        return valueText;
    }

    void setValueText(String valueText) {
        this.valueText = valueText;
    }

    LocalDate getValueDate() {
        return valueDate;
    }

    void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    BigDecimal getValueAmount() {
        return valueAmount;
    }

    void setValueAmount(BigDecimal valueAmount) {
        this.valueAmount = valueAmount;
    }

    String getValueCurrency() {
        return valueCurrency;
    }

    void setValueCurrency(String valueCurrency) {
        this.valueCurrency = valueCurrency;
    }

    String getValuePeriod() {
        return valuePeriod;
    }

    void setValuePeriod(String valuePeriod) {
        this.valuePeriod = valuePeriod;
    }

    Boolean getValueFlag() {
        return valueFlag;
    }

    void setValueFlag(Boolean valueFlag) {
        this.valueFlag = valueFlag;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    LocalDate getValidUntil() {
        return validUntil;
    }

    void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    String getReviewState() {
        return reviewState;
    }

    void setReviewState(String reviewState) {
        this.reviewState = reviewState;
    }

    String getOrigin() {
        return origin;
    }

    void setOrigin(String origin) {
        this.origin = origin;
    }

    Double getConfidence() {
        return confidence;
    }

    void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    void setSourceDocumentId(UUID sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
    }

    Integer getSourcePage() {
        return sourcePage;
    }

    void setSourcePage(Integer sourcePage) {
        this.sourcePage = sourcePage;
    }

    String getSourceQuote() {
        return sourceQuote;
    }

    void setSourceQuote(String sourceQuote) {
        this.sourceQuote = sourceQuote;
    }

    String getRunRef() {
        return runRef;
    }

    void setRunRef(String runRef) {
        this.runRef = runRef;
    }

    UUID getObservedBy() {
        return observedBy;
    }

    void setObservedBy(UUID observedBy) {
        this.observedBy = observedBy;
    }

    Instant getObservedAt() {
        return observedAt;
    }

    void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    UUID getDecidedBy() {
        return decidedBy;
    }

    void setDecidedBy(UUID decidedBy) {
        this.decidedBy = decidedBy;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    UUID getSupersededBy() {
        return supersededBy;
    }

    void setSupersededBy(UUID supersededBy) {
        this.supersededBy = supersededBy;
    }

    UUID getCorrects() {
        return corrects;
    }

    void setCorrects(UUID corrects) {
        this.corrects = corrects;
    }

    String getNote() {
        return note;
    }

    void setNote(String note) {
        this.note = note;
    }
}
