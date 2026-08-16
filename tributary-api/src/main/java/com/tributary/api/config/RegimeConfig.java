package com.tributary.api.config;

import com.tributary.adapter.co.FactusCredentials;
import com.tributary.adapter.co.FactusEnvironment;
import com.tributary.adapter.co.FactusFiscalRegimeAdapter;
import com.tributary.adapter.es.VerifactuFiscalRegimeAdapter;
import com.tributary.application.port.FiscalRecordPort;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.persistence.FiscalRecordRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SRS 3: a single issuer, no multi-tenancy — exactly one {@link FiscalRegimePort} is wired per
 * deployment (never a per-request choice), matching how every use case in {@code
 * tributary-application} already takes a single {@code FiscalRegimePort} rather than a
 * regime-keyed map. {@code tributary.regime} picks which real implementation backs it; DE has none
 * (RF-005's XRechnung rendering is a delivery-format capability independent of which regime
 * clears the document, wired separately in {@link RenderingConfig}).
 */
@Configuration
public class RegimeConfig {

  @Bean
  public FiscalRecordPort fiscalRecordPort(DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new FiscalRecordRepository(dataSource, transactionManager);
  }

  @Bean
  public FiscalRegimePort fiscalRegimePort(
      @Value("${tributary.regime}") String regime,
      InvoiceRepository invoiceRepository,
      FiscalRecordPort fiscalRecordPort) {
    return switch (regime) {
      case "ES" -> new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, Clock.systemUTC());
      case "CO" -> {
        FactusCredentials credentials = FactusEnvironment.resolve(System::getenv);
        // 389 = "Factura de Venta", confirmed live against the real Factus sandbox (T-300 series).
        yield new FactusFiscalRegimeAdapter(credentials, 389);
      }
      default ->
          throw new IllegalStateException(
              "tributary.regime=\"" + regime + "\" is not a valid issuance regime — expected ES or CO "
                  + "(DE has no FiscalRegimePort: RF-005's XRechnung rendering is a separate, always-available capability)");
    };
  }

  @Bean
  public String configuredRegimeName(@Value("${tributary.regime}") String regime) {
    return regime;
  }
}
