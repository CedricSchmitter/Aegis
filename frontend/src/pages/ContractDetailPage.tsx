import { useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { BackLink } from "../components/common";
import { ContractView } from "../components/contracts/ContractView";

export function ContractDetailPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <BackLink to="/contracts">{t("contracts.title")}</BackLink>
      <ContractView
        key={id}
        id={id}
        heading={(name) => <h1 className="text-2xl leading-tight font-bold break-words lg:text-[28px]">{name}</h1>}
      />
    </div>
  );
}
