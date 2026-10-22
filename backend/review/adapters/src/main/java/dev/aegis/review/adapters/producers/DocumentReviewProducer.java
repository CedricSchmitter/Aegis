package dev.aegis.review.adapters.producers;

import dev.aegis.documents.application.api.*; import dev.aegis.review.application.ports.ReviewProducer; import dev.aegis.review.domain.ReviewItem.Candidate; import dev.aegis.shared.Actor; import java.util.*; import org.springframework.stereotype.Component;

@Component
public class DocumentReviewProducer implements ReviewProducer {
 private final DocumentQueries documents; private final BulkAnalysisQueries bulks; private final SimilarDocumentQueries similar; public DocumentReviewProducer(DocumentQueries documents,BulkAnalysisQueries bulks,SimilarDocumentQueries similar){this.documents=documents;this.bulks=bulks;this.similar=similar;} @Override public String id(){return "documents";}
 @Override public List<Candidate> findCurrent(Actor actor){List<Candidate> out=new ArrayList<>();int page=0;DocumentSearchResult result;do{result=documents.search(actor,new DocumentSearchRequest("",null,true,false,null,null,null,null,null,false,false,null,"NEWEST",page++,100));for(DocumentSummary d:result.items())collect(d,out);}while((long)page*100<result.matchingDocuments());
  // A bulk classification gets one card once it ended, instead of one card per document it touched.
  for(BulkAnalysisQueries.Attention b:bulks.attention(actor)){String id=b.id().toString();out.add(new Candidate("bulk-analysis:"+id,id(),"BULK_ANALYSIS",id,1,"CLASSIFICATION","BULK_CLASSIFICATION","NORMAL","review.bulkAnalysis",Map.of("total",String.valueOf(b.total()),"classified",String.valueOf(b.classified()),"needsReview",String.valueOf(b.needsReview()),"failed",String.valueOf(b.failed())),"BULK_ANALYSIS",id,List.of()));}
  // One card per pair of probably identical documents (ADR-0028), linked to the earlier upload; the pair is its own
  // version, so a dismissal holds while both stay alike, and trashing either one supersedes the card.
  for(SimilarDocumentQueries.SimilarPair p:similar.similarPairs(actor)){String a=p.first().toString(),b=p.second().toString();out.add(candidate("similar:"+a+":"+b,a,1,"DUPLICATE","SIMILAR_DOCUMENT","NORMAL","review.similarDocument",Map.of("title",p.firstTitle(),"otherTitle",p.secondTitle()),List.of()));}
  return out;}
 private void collect(DocumentSummary d,List<Candidate> out){String id=d.id().toString();if("FAILED".equals(d.processing().state()))out.add(candidate("archive:"+id+":"+d.version()+":"+d.processing().lastErrorCode(),id,d.version(),"PROCESSING_FAILURE","ARCHIVAL_FAILED","HIGH","review.archiveFailed",Map.of("title",d.title(),"errorCode",safe(d.processing().lastErrorCode())),List.of("RETRY_ARCHIVAL")));
  // NOT_CLASSIFIED (the requester may not use AI) is no failure and gets no card; reconciliation
  // supersedes cards created for it before.
  // Results of a bulk classification are grouped into the run's card above.
  AnalysisView a=d.analysis(); boolean bulk="BULK".equals(a.trigger()); if(!bulk&&(AnalysisView.FAILED.equals(a.state())||AnalysisView.UNAVAILABLE.equals(a.state())))out.add(candidate("analysis-failed:"+id+":"+a.version()+":"+a.failureCode(),id,a.version(),"PROCESSING_FAILURE","ANALYSIS_FAILED","NORMAL","review.analysisFailed",Map.of("title",d.title(),"errorCode",safe(a.failureCode())),List.of("RETRY_ANALYSIS")));
  if(!bulk&&AnalysisView.NEEDS_REVIEW.equals(a.state()))out.add(candidate("classification:"+id+":"+a.version(),id,a.version(),"CLASSIFICATION","UNCERTAIN_CLASSIFICATION","NORMAL","review.classification",Map.of("title",d.title()),List.of()));
  // One card per archive document without recognized text (ADR-0012); the archive document id is its source version.
  if(d.textRecognition().lacksText())out.add(candidate("no-text:"+id+":"+d.processing().paperlessDocumentId(),id,d.version(),"PROCESSING_FAILURE","NO_TEXT_RECOGNIZED","NORMAL","review.noText",Map.of("title",d.title()),List.of()));}
 private Candidate candidate(String f,String id,long v,String category,String reason,String priority,String key,Map<String,String> params,List<String> actions){return new Candidate(f,id(),"DOCUMENT",id,v,category,reason,priority,key,params,"DOCUMENT",id,actions);} private static String safe(String s){return s==null?"unknown":s;}
}
