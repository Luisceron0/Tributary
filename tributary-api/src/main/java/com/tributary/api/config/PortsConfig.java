package com.tributary.api.config;

import com.tributary.application.port.AuditEventPort;
import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.KeyVaultPort;
import com.tributary.persistence.JdbcAuditEventRepository;
import com.tributary.persistence.JdbcInvoiceRepository;
import com.tributary.persistence.JdbcIssuanceAttemptRepository;
import com.tributary.persistence.JdbcKeyVaultRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The real, PostgreSQL-backed implementation of every persistence port. One bean per class, not
 * per interface: {@link JdbcInvoiceRepository} alone implements both {@code InvoiceRepository}
 * and {@code RetentionCheckPort} (T-602) — declaring it once here lets Spring satisfy both
 * injection points from the same instance, rather than two beans that happen to agree.
 */
@Configuration
public class PortsConfig {

  @Bean
  public KeyVaultPort keyVaultPort(DataSource dataSource) {
    return new JdbcKeyVaultRepository(dataSource);
  }

  @Bean
  public JdbcInvoiceRepository invoiceRepository(DataSource dataSource, KeyVaultPort keyVaultPort) {
    return new JdbcInvoiceRepository(dataSource, keyVaultPort);
  }

  @Bean
  public IssuanceAttemptPort issuanceAttemptPort(DataSource dataSource) {
    return new JdbcIssuanceAttemptRepository(dataSource);
  }

  @Bean
  public AuditEventPort auditEventPort(DataSource dataSource) {
    return new JdbcAuditEventRepository(dataSource);
  }
}
