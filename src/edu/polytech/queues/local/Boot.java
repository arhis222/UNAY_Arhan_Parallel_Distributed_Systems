package edu.polytech.queues.local;

import edu.polytech.queues.Bootstrap;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

public class Boot implements Bootstrap {
    public Boot() {}

    @Override
    public Task newTask(Runnable r, String name) {
        if (r == null || name == null) throw new IllegalArgumentException();
        Executor executor = Executor.self();
        synchronized (executor) {
            Task task = executor.newTask(name);
            task.post(r);
            return task;
        }
    }
}
