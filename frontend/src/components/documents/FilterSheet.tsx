import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { FacetCounts, LabelDto, MemberDto } from "../../api/types";
import { facetFilterCount, withoutFacets, type CatalogFilters } from "../../lib/catalogSearch";
import { FacetFilters } from "../FacetFilters";
import { Sheet } from "../ui";

interface Props {
  open: boolean;
  onClose: () => void;
  filters: CatalogFilters;
  facets: FacetCounts | null;
  matching: number;
  partial?: boolean;
  labels: LabelDto[];
  members: MemberDto[];
  onChange: (filters: CatalogFilters) => void;
}

/**
 * The label filters in a sheet: values apply at once, the button shows the hits, and "Manage
 * labels" lives here, next to the labels it manages.
 */
export function FilterSheet({ open, onClose, filters, facets, matching, partial = false, labels, members, onChange }: Props) {
  const { t } = useTranslation();
  return (
    <Sheet open={open} onClose={onClose} title={t("documents.filters")} testId="filter-sheet">
      {partial && <p className="alert-warn" role="status">{t("documents.partialFacets")}</p>}
      {facets && <FacetFilters partial={partial} filters={filters} facets={facets} labels={labels} members={members} onChange={onChange} />}
      <div className="flex flex-wrap gap-2 border-t border-line pt-4">
        <button type="button" className="btn-primary" onClick={onClose} data-testid="filters-show">
          {t(partial ? "documents.showResultsLowerBound" : "documents.showResults", { count: matching })}
        </button>
        {facetFilterCount(filters) > 0 && (
          <button type="button" className="btn-ghost" onClick={() => onChange(withoutFacets(filters))}>
            {t("documents.clearFilters")}
          </button>
        )}
        <Link to="/documents/labels" className="btn-secondary" data-testid="manage-labels">
          {t("documents.manageLabels")}
        </Link>
      </div>
    </Sheet>
  );
}
