package io.quarkus.hibernate.orm.runtime.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.SelectionQuery;

import io.quarkus.runtime.LaunchMode;

public class HibernateOrmDevJsonRpcService {
    private boolean isDev = false;

    public HibernateOrmDevJsonRpcService() {
        this.isDev = LaunchMode.current() == LaunchMode.DEVELOPMENT && !LaunchMode.isRemoteDev();
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

    private Optional<HibernateOrmDevInfo.PersistenceUnit> findPersistenceUnit(String persistenceUnitName) {
        return getInfo().getPersistenceUnits().stream().filter(pu -> persistenceUnitName.equals(pu.getName())).findFirst();
    }

    public DataSet executeHQL(String persistenceUnit, String hql, Integer pageNumber, Integer pageSize) {
        if (isDev && hqlIsValid(hql)) {
            Optional<HibernateOrmDevInfo.PersistenceUnit> pu = findPersistenceUnit(persistenceUnit);
            if (pu.isPresent()) {
                //noinspection resource
                SessionFactoryImplementor sf = pu.get().sessionFactory();
                return sf.fromSession(session -> {
                    try {
                        SelectionQuery<Object> query = session.createSelectionQuery(hql, Object.class);

                        // execute count query before applying offset and limit
                        long resultCount = query.getResultCount();

                        try (ScrollableResults<Object> scroll = query.scroll(ScrollMode.SCROLL_INSENSITIVE)) {
                            scroll.scroll((pageNumber - 1) * pageSize + 1);
                            List<Object> results = new ArrayList<>();
                            int i = 0;
                            while (pageSize > i++) {
                                results.add(scroll.get());
                                if (!scroll.next())
                                    break;
                            }

                            // todo : for now we rely on automatic marshalling of results
                            return new DataSet(results, resultCount, null);
                        }
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
        return hql != null && !hql.trim().isEmpty();
    }

    private record DataSet(List<Object> data, long totalNumberOfElements, String error) {
    }
}
