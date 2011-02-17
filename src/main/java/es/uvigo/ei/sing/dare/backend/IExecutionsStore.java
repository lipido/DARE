package es.uvigo.ei.sing.dare.backend;

import es.uvigo.ei.sing.dare.resources.PeriodicalExecution;

public interface IExecutionsStore {

    /**
     * Finds a periodical execution with the specified code. If not found
     * returns <code>null</code>.
     *
     * @param code
     * @return
     */
    PeriodicalExecution findPeriodicalExecution(String code);

}
