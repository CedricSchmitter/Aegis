package dev.aegis.review.adapters.producers;

import dev.aegis.documents.application.api.*; import dev.aegis.review.application.ports.ReviewProducer; import dev.aegis.review.domain.ReviewItem.Candidate; import dev.aegis.shared.Actor; import java.util.*; import org.springframework.stereotype.Component;

@Component
public class DocumentReviewProducer implements ReviewProducer {
 private final DocumentQueries documents; public DocumentReviewProducer(DocumentQueries documents){this.documents=documents;} @Override public String id(){return "documents";}
 @Override public List<Candidate> findCurrent(Actor actor){List<Candidate> out=new ArrayList<>();int page=0;DocumentSearchResult result;do{result=documents.search(actor,new DocumentSearchRequest("",null,true,false,null,null,null,null,null,false,false,null,"NEWEST",page++,100));for(DocumentSummary d:result.items())collect(d,out);}while((long)page*100<result.matchingDocuments());return out;}
 private void collect(DocumentSummary d,List<Candidate> out){String id=d.id().toString();if("FAILED".equals(d.processing().state()))out.add(candidate("archive:"+id+":"+d.version()+":"+d.processing().lastErrorCode(),id,d.version(),"PROCESSING_FAILURE","ARCHIVAL_FAILED","HIGH","review.archiveFailed",Map.of("title",d.title(),"errorCode",safe(d.processing().lastErrorCode())),List.of("RETRY_ARCHIVAL")));
  AnalysisView a=d.analysis(); if(AnalysisView.FAILED.equals(a.state())||AnalysisView.UNAVAILABLE.equals(a.state()))out.add(candidate("analysis-failed:"+id+":"+a.version()+":"+a.failureCode(),id,a.version(),"PROCESSING_FAILURE","ANALYSIS_FAILED","NORMAL","review.analysisFailed",Map.of("title",d.title(),"errorCode",safe(a.failureCode())),List.of("RETRY_ANALYSIS")));
  if(AnalysisView.NEEDS_REVIEW.equals(a.state()))out.add(candidate("classification:"+id+":"+a.version(),id,a.version(),"CLASSIFICATION","UNCERTAIN_CLASSIFICATION","NORMAL","review.classification",Map.of("title",d.title()),List.of()));}
 private Candidate candidate(String f,String id,long v,String category,String reason,String priority,String key,Map<String,String> params,List<String> actions){return new Candidate(f,id(),"DOCUMENT",id,v,category,reason,priority,key,params,"DOCUMENT",id,actions);} private static String safe(String s){return s==null?"unknown":s;}
}
