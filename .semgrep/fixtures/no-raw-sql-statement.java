package com.tributary.semgreptest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

class VulnerableRepo {

  void unsafe(Connection conn, String businessKey) throws SQLException {
    // ruleid: tributary-no-raw-sql-statement
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM invoice WHERE business_key = '" + businessKey + "'");
  }

  void safe(Connection conn, String businessKey) throws SQLException {
    // ok: tributary-no-raw-sql-statement
    PreparedStatement ps = conn.prepareStatement("SELECT * FROM invoice WHERE business_key = ?");
    ps.setString(1, businessKey);
  }
}
