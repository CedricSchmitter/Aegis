package dev.aegis.bootstrap.config;
import dev.aegis.documents.application.*;
import dev.aegis.documents.application.ports.*;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class PassageIndexConfiguration {
    @Bean(destroyMethod="close")
    DocumentTextExtractor documentTextExtractor(PaperlessArchive archive, OriginalStore store, PdfText pdf) {
        return new DocumentTextExtractor(archive,store,pdf);
    }
    @Bean
    PassageService passageService(PassageIndex index, HouseholdAccess access, Clock clock) {
        return new PassageService(index,access,clock);
    }
    @Bean
    PassageIndexWorker passageIndexWorker(PassageIndex index, DocumentRepository documents, DocumentTextExtractor extractor, Clock clock) {
        return new PassageIndexWorker(index,documents,extractor,clock);
    }
}
