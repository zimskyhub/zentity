/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.zmtech.zkit.transaction;


import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.entity.EntityFacade;
import com.zmtech.zkit.util.MNode;

import javax.sql.DataSource;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

/**
 * 内部事务
 */
public interface TransactionInternal {

    TransactionInternal init(ExecutionContextFactory ecf);
    TransactionManager getTransactionManager();
    UserTransaction getUserTransaction();
    DataSource getDataSource(EntityFacade ef, MNode datasourceNode);

    void destroy();
}
