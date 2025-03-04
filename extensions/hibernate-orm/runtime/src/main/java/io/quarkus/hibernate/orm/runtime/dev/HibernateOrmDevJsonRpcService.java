package io.quarkus.hibernate.orm.runtime.dev;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.SelectionQuery;

import io.quarkus.runtime.LaunchMode;

public class HibernateOrmDevJsonRpcService {
    private boolean isDev = false;
    private boolean allowHql = false;

    public HibernateOrmDevJsonRpcService() {
        this.isDev = LaunchMode.current() == LaunchMode.DEVELOPMENT && !LaunchMode.isRemoteDev();
        Config config = ConfigProvider.getConfig();
        this.allowHql = config.getOptionalValue("quarkus.hibernate-orm.dev-ui.allow-hql", Boolean.class).orElse(false);
    }

    public HibernateOrmDevInfo getInfo() {
        return HibernateOrmDevController.get().getInfo();
    }

    public int getNumberOfPersistenceUnits() {
        return getInfo().getPersistenceUnits().size();
    }

    public int getNumberOfEntityTypes() {
        return getInfo().getNumberOfEntities();
    }

    public int getNumberOfNamedQueries() {
        return getInfo().getNumberOfNamedQueries();
    }

    private Optional<HibernateOrmDevInfo.PersistenceUnit> getPersistenceUnit(String persistenceUnitName) {
        return getInfo().getPersistenceUnits().stream().filter(pu -> persistenceUnitName.equals(pu.getName())).findFirst();
    }

    public DataSet executeSQL(String persistenceUnit, String hql, Integer pageNumber, Integer pageSize) {
        if (isDev && hqlIsValid(hql)) {
            Optional<HibernateOrmDevInfo.PersistenceUnit> pu = getPersistenceUnit(persistenceUnit);
            if (pu.isPresent()) {
                SessionFactoryImplementor sf = pu.get().getSessionFactory();
                return sf.fromTransaction(session -> {
                    try {
                        SelectionQuery<Object> query = session.createSelectionQuery(hql, Object.class);

                        // execute count query before applying offset and limit
                        long resultCount = query.getResultCount();

                        query.setFirstResult((pageNumber - 1) * pageSize + 1);
                        query.setMaxResults(pageSize);
                        List<Object> resultList = query.getResultList();

                        // todo marco : for now we rely on automatic marshalling of results
                        //  when ready, we should use the custom Hibernate Serializer
                        return new DataSet(resultList, resultCount, null);
                    } catch (Exception ex) {
                        return new DataSet(null, -1, ex.getMessage());
                    }
                });
            } else {
                return new DataSet(null, -1, "The provided persistence unit name was not found");
            }
        } else {
            return new DataSet(null, -1, "The provided HQL was not valid");
        }
    }

    private boolean hqlIsValid(String hql) {
        return allowHql && hql != null && !hql.trim().isEmpty();
    }

    private record DataSet(List<Object> data, long totalNumberOfElements, String error) {
    }
}
