package dev.aegis.review.adapters.producers;

import dev.aegis.contracts.application.api.*; import dev.aegis.review.application.ports.ReviewProducer; import dev.aegis.review.domain.ReviewItem.Candidate; import dev.aegis.shared.Actor; import java.util.*; import org.springframework.stereotype.Component;

@Component
public class ContractReviewProducer implements ReviewProducer {
 private final ContractQueries contracts; public ContractReviewProducer(ContractQueries contracts){this.contracts=contracts;} @Override public String id(){return "contracts";}
 @Override public List<Candidate> findCurrent(Actor actor){List<Candidate> out=new ArrayList<>();for(var summary:contracts.list(actor)){if(summary.pendingFacts()==0&&summary.conflicts()==0)continue;var c=contracts.get(actor,summary.id());for(var fact:c.facts()){if("EXTRACTED".equals(fact.reviewState()))out.add(new Candidate("contract-fact:"+c.id()+":"+fact.id()+":"+c.version(),id(),"CONTRACT",c.id().toString(),c.version(),"CONTRACT_FACT","UNCONFIRMED_FACT","NORMAL","review.contractFact",Map.of("contract",c.name(),"type",fact.type(),"value",fact.value()),"CONTRACT",c.id().toString(),List.of()));}for(var conflict:c.conflicts())out.add(new Candidate("contract-conflict:"+c.id()+":"+conflict.type()+":"+c.version(),id(),"CONTRACT",c.id().toString(),c.version(),"CONTRACT_FACT","CONFLICTING_FACTS","HIGH","review.contractConflict",Map.of("contract",c.name(),"type",conflict.type()),"CONTRACT",c.id().toString(),List.of()));}return out;}
}
