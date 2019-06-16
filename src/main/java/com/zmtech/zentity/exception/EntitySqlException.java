package com.zmtech.zentity.exception;

import com.zmtech.zentity.entity.EntityContext;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrap an SqlException for more user friendly error messages
 */
public class EntitySqlException extends EntityException {
    // NOTE these are the messages to localize with LocalizedMessage
    // NOTE: don't change these unless there is a really good reason, will break localization
    private static Map<String, String> messageBySqlCode = new ConcurrentHashMap<String, String>() {{
        put("22", "invalid data");                          // data exception
        put("22001", "text value too long"); // VALUE_TOO_LONG, char/varchar/etc (aka right truncation)
        put("22003", "number too big"); // NUMERIC_VALUE_OUT_OF_RANGE
        put("22004", "empty value not allowed"); // null value not allowed
        put("22018", "text value could not be converted"); // DATA_CONVERSION_ERROR, invalid character value for cast
        put("23", "record already exists or related record does not exist"); // integrity constraint violation, most likely problems
        put("23502", "empty value not allowed"); // NULL_NOT_ALLOWED
        put("23503", "tried to delete record that other records refer to or record specified does not exist"); // REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS (in update or delete would orphan FK)
        // NOTE: Postgres uses 23503 for parent and child fk violations, other DBs too? use same message for both
        put("23505", "record already exists"); // DUPLICATE_KEY
        put("23506", "record specified does not exist"); // REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING (in insert or update invalid FK reference)
        put("40", "record lock conflict found"); // transaction rollback
        put("40001", "record lock conflict found"); // DEADLOCK - serialization failure
        put("40002", "record lock conflict found"); // integrity constraint violation
        put("40P01", "record lock conflict found"); // postgres deadlock_detected
        put("50200", "timeout waiting for record lock"); // LOCK_TIMEOUT H2
        put("57033", "record lock conflict found"); // DB2 deadlock without automatic rollback
        put("HY", "timeout waiting for database"); // lock or other timeout; is this really correct for this 2 letter code?
        put("HY000", "timeout waiting for record lock"); // lock or other timeout
        put("HYT00", "timeout waiting for record lock"); // lock or other timeout (H2)
        // NOTE MySQL uses HY000 for a LOT of stuff, lock timeout distinguished by error code 1205
    }};

    /* see:
        https://www.h2database.com/javadoc/org/h2/api/ErrorCode.html
        https://dev.mysql.com/doc/refman/5.7/en/error-messages-server.html
        https://www.postgresql.org/docs/current/static/errcodes-appendix.html
        https://www.ibm.com/support/knowledgecenter/SSEPEK_12.0.0/codes/src/tpc/db2z_sqlstatevalues.html
     */

    private String sqlState = null;

    public EntitySqlException(String str, SQLException nested) {
        super(str, nested);
        getSQLState(nested);
    }

    @Override
    public String getMessage() {
        String overrideMessage = super.getMessage();
        if (sqlState != null) {
            // try full string
            String msg = messageBySqlCode.get(sqlState);
            // try first 2 chars
            if (msg == null && sqlState.length() >= 2) msg = messageBySqlCode.get(sqlState.substring(0, 2));
            // localize and append
            if (msg != null) {
                try {
                    EntityContext ec = Moqui.getEntityContext()
                    // TODO: need a different approach for localization, getting from DB may not be reliable after an error and may cause other errors (especially with Postgres and the auto rollback only)
                    // overrideMessage += ': ' + ec.l10n.localize(msg)
                    overrideMessage += ": " + msg;
                } catch (Throwable t) {
                    System.out.println("Error localizing override message " + t.toString());
                }
            }
        }
        overrideMessage += " [" + sqlState + "]";
        return overrideMessage;
    }

    @Override
    public String toString() {
        return getMessage();
    }

    public String getSQLState() {
        return sqlState;
    }

    public String getSQLState(SQLException ex) {
        if (sqlState != null) return sqlState;
        sqlState = ex.getSQLState();
        if (sqlState == null) {
            SQLException nestedEx = ex.getNextException();
            if (nestedEx != null) sqlState = nestedEx.getSQLState();
        }
        return sqlState;
    }
}
