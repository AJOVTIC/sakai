/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2003, 2004, 2005, 2006, 2007 The Sakai Foundation.
 *
 * Licensed under the Educational Community License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/ecl1.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.search.journal.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.search.indexer.api.IndexJournalException;
import org.sakaiproject.search.journal.api.JournalErrorException;
import org.sakaiproject.search.journal.api.JournalExhausetedException;
import org.sakaiproject.search.journal.api.JournalManager;
import org.sakaiproject.search.journal.api.JournalManagerState;
import org.sakaiproject.search.transaction.api.IndexTransaction;

/**
 * A database backed Journal Manager
 * 
 * @author ieb Unit test
 * @see org.sakaiproject.search.indexer.impl.test.TransactionalIndexWorkerTest
 *      Unit test
 * @see org.sakaiproject.search.indexer.impl.test.DbJournalManagerTest
 */
public class DbJournalManager implements JournalManager
{

	/**
	 * @author ieb
	 */
	public class JournalManagerStateImpl implements JournalManagerState
	{

		private long transactionId;

		/*
		 * Normally its not safe to hold onto a connection for any length of time, but here it is ok
		 * since the transaction is connected to the transaction id. The danger of not detaching is connection
		 * is that if there is a machine failure I dont journal to be update.
		 */
		public Connection connection;

		/**
		 * @param transactionId
		 */
		public JournalManagerStateImpl(long transactionId)
		{
			this.transactionId = transactionId;
		}

		/**
		 * @return the transactionId
		 */
		public long getTransactionId()
		{
			return transactionId;
		}

	}

	private static final Log log = LogFactory.getLog(DbJournalManager.class);

	private DataSource datasource;

	/**
	 * @return the datasource
	 */
	public DataSource getDatasource()
	{
		return datasource;
	}

	/**
	 * @param datasource
	 *        the datasource to set
	 */
	public void setDatasource(DataSource datasource)
	{
		this.datasource = datasource;
	}

	/**
	 * @throws JournalErrorException
	 * @see org.sakaiproject.search.journal.api.JournalManager#getLaterSavePoints(long)
	 */
	public long getNextSavePoint(long savePoint) throws JournalErrorException
	{
		Connection connection = null;
		PreparedStatement listLaterSavePoints = null;
		ResultSet rs = null;
		try
		{
			connection = datasource.getConnection();
			listLaterSavePoints = connection
					.prepareStatement("select txid from search_journal where txid > ? and status = 'commited' order by txid asc ");
			listLaterSavePoints.clearParameters();
			listLaterSavePoints.setLong(1, savePoint);
			rs = listLaterSavePoints.executeQuery();
			if (rs.next())
			{
				return rs.getLong(1);
			}
			throw new JournalExhausetedException("No More savePoints available");
		}
		catch (SQLException ex)
		{
			log.error("Failed to retrieve list of journal items ", ex);
			throw new JournalErrorException("Journal Error ", ex);
		}
		finally
		{
			try
			{
				rs.close();
			}
			catch (Exception ex)
			{
			}
			try
			{
				listLaterSavePoints.close();
			}
			catch (Exception ex)
			{
			}
			try
			{
				connection.close();
			}
			catch (Exception ex)
			{
			}
		}
	}

	/**
	 * @see org.sakaiproject.search.journal.api.JournalManager#prepareSave(long)
	 */
	public JournalManagerState prepareSave(long transactionId)
			throws IndexJournalException
	{
		PreparedStatement insertPst = null;
		JournalManagerStateImpl jms = new JournalManagerStateImpl(transactionId);
		try
		{

			Connection connection = datasource.getConnection();
			jms.connection = connection;

			insertPst = connection
					.prepareStatement("insert into search_journal (txid, txts, indexwriter, status) values ( ?,?,?,?)");
			insertPst.clearParameters();
			insertPst.setLong(1, transactionId);
			insertPst.setLong(2, System.currentTimeMillis());
			insertPst.setString(3, String.valueOf(Thread.currentThread().getName()));
			insertPst.setString(4, "prepare");
			if (insertPst.executeUpdate() != 1)
			{
				throw new IndexJournalException("Failed to update index journal");
			}

		}
		catch (IndexJournalException ijex)
		{
			throw ijex;
		}
		catch (Exception ex)
		{
			throw new IndexJournalException("Failed to transfer index ", ex);
		}
		finally
		{
			try
			{
				insertPst.close();
			}
			catch (Exception ex)
			{
			}
		}
		return jms;
	}

	/**
	 * @see org.sakaiproject.search.journal.api.JournalManager#commitSave()
	 */
	public void commitSave(JournalManagerState jms) throws IndexJournalException
	{
		Connection connection = ((JournalManagerStateImpl) jms).connection;
		PreparedStatement success = null;
		try
		{
			success = connection
					.prepareStatement("update search_journal set status = 'commited' where txid = ? ");
			success.clearParameters();
			success.setLong(1, ((JournalManagerStateImpl) jms).getTransactionId());
			if (success.executeUpdate() != 1)
			{
				throw new IndexJournalException("Failed to update index journal");
			}

			connection.commit();
		}
		catch (Exception ex)
		{
			try
			{
				connection.rollback();
			}
			catch (Exception ex2)
			{
			}
			throw new IndexJournalException("Failed to commit index ", ex);
		}
		finally
		{
			try
			{
				connection.close();
			}
			catch (Exception ex)
			{
			}
		}

	}

	/**
	 * @see org.sakaiproject.search.journal.api.JournalManager#rollbackSave(org.sakaiproject.search.journal.api.JournalManagerState)
	 */
	public void rollbackSave(JournalManagerState jms)
	{
		Connection connection = ((JournalManagerStateImpl) jms).connection;
		try
		{

			connection.rollback();

		}
		catch (Exception ex)
		{
			log.error("Failed to Rollback");
		}
		finally
		{
			try
			{
				connection.close();
			}
			catch (Exception ex)
			{
			}

		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sakaiproject.search.journal.api.JournalManager#doOpenTransaction(org.sakaiproject.search.transaction.api.IndexTransaction)
	 */
	public void doOpenTransaction(IndexTransaction transaction)
	{
	}

}
