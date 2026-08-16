package com.tributary.api.config;

import com.tributary.application.port.AuditEventPort;
import com.tributary.application.port.FiscalRecordPort;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.KeyVaultPort;
import com.tributary.application.port.RetentionCheckPort;
import com.tributary.application.usecase.CorrectInvoiceUseCase;
import com.tributary.application.usecase.GetInvoiceUseCase;
import com.tributary.application.usecase.GetRecordVerificationUseCase;
import com.tributary.application.usecase.IssueInvoiceUseCase;
import com.tributary.application.usecase.RegisterInvoiceUseCase;
import com.tributary.application.usecase.SuppressPersonalDataUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One bean per use case, each taking its ports by constructor — no use case knows Spring exists. */
@Configuration
public class UseCaseConfig {

  @Bean
  public RegisterInvoiceUseCase registerInvoiceUseCase(InvoiceRepository invoiceRepository) {
    return new RegisterInvoiceUseCase(invoiceRepository);
  }

  @Bean
  public IssueInvoiceUseCase issueInvoiceUseCase(
      InvoiceRepository invoiceRepository,
      IssuanceAttemptPort issuanceAttemptPort,
      FiscalRegimePort fiscalRegimePort,
      String configuredRegimeName) {
    return new IssueInvoiceUseCase(invoiceRepository, issuanceAttemptPort, fiscalRegimePort, configuredRegimeName);
  }

  @Bean
  public CorrectInvoiceUseCase correctInvoiceUseCase(
      InvoiceRepository invoiceRepository, FiscalRegimePort fiscalRegimePort, AuditEventPort auditEventPort) {
    return new CorrectInvoiceUseCase(invoiceRepository, fiscalRegimePort, auditEventPort);
  }

  @Bean
  public GetInvoiceUseCase getInvoiceUseCase(InvoiceRepository invoiceRepository) {
    return new GetInvoiceUseCase(invoiceRepository);
  }

  @Bean
  public GetRecordVerificationUseCase getRecordVerificationUseCase(FiscalRecordPort fiscalRecordPort) {
    return new GetRecordVerificationUseCase(fiscalRecordPort);
  }

  @Bean
  public SuppressPersonalDataUseCase suppressPersonalDataUseCase(
      RetentionCheckPort retentionCheckPort, KeyVaultPort keyVaultPort, AuditEventPort auditEventPort) {
    return new SuppressPersonalDataUseCase(retentionCheckPort, keyVaultPort, auditEventPort);
  }
}
